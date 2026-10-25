package com.example.agent.plugin;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * 为测试构建插件 JAR：用 JDK 自带的编译器（javax.tools）按测试类路径编译源码，再和清单一起打包。
 * 编译出的插件类不在测试类路径上，所以只有 {@link PluginClassLoader} 能加载它们——和真实部署一样。
 */
public final class TestPlugins {

    private static final Map<Map<String, String>, Path> COMPILED = new HashMap<>();

    private TestPlugins() {
    }

    /**
     * {@code test.<id>} 包里的插件，入口注册 {@code <id>_echo} 和 {@code <id>_write}（{@code @Tool(requiresConfirmation = true)}）。
     * echo 返回配置 {@code greeting}、插件版本、调用方身份，以及线程上下文类加载器是不是插件自己的。为了测试，它还读取
     * ToolContext 里的几个键：{@code latch} 下的 CountDownLatch 会被等待，{@code loader} 下的 Consumer 会收到它的类加载器，
     * 文本为 "fail" 时抛出受检异常。
     */
    public static String echoPlugin(String id) {
        return """
                package test.%1$s;

                import com.example.agent.plugin.api.AgentPlugin;
                import com.example.agent.plugin.api.PluginContext;
                import java.util.concurrent.CountDownLatch;
                import java.util.function.Consumer;
                import org.springframework.ai.chat.model.ToolContext;
                import org.springframework.ai.tool.annotation.Tool;
                import org.springframework.ai.tool.annotation.ToolParam;

                public class Plugin implements AgentPlugin {
                    @Override
                    public void start(PluginContext context) {
                        context.registerTools(new Tools(context.version(), context.config().getOrDefault("greeting", "hello")));
                    }

                    public static class Tools {
                        private final String version;
                        private final String greeting;

                        Tools(String version, String greeting) {
                            this.version = version;
                            this.greeting = greeting;
                        }

                        @Tool(name = "%2$s_echo", description = "Echoes the text with the plugin version")
                        @SuppressWarnings("unchecked")
                        public String echo(@ToolParam(description = "Text to echo") String text, ToolContext context) throws Exception {
                            if (context.getContext().get("loader") instanceof Consumer<?> sink) {
                                ((Consumer<ClassLoader>) sink).accept(Tools.class.getClassLoader());
                            }
                            if (context.getContext().get("latch") instanceof CountDownLatch latch) latch.await();
                            if ("fail".equals(text)) throw new java.io.IOException("echo failed");
                            boolean pluginLoader = Thread.currentThread().getContextClassLoader() == Tools.class.getClassLoader();
                            return greeting + " " + text + " v" + version + " user=" + context.getContext().get("userId")
                                    + " session=" + context.getContext().get("sessionId") + " tccl=" + pluginLoader;
                        }

                        @Tool(name = "%2$s_write", description = "Pretends to write the text", requiresConfirmation = true)
                        public String write(@ToolParam(description = "Text to write") String text) {
                            return "wrote " + text + " v" + version;
                        }
                    }
                }
                """.formatted(javaPackage(id), id);
    }

    /** {@link #echoPlugin} 的清单：声明两个工具，confirmEcho 为 true 时清单要求 {@code <id>_echo} 审批。 */
    public static String echoManifest(String id, String version, boolean confirmEcho, String config) {
        return manifest(id, version, "test." + javaPackage(id) + ".Plugin", """
                [{"name":"%1$s_echo","requiresConfirmation":%2$s},{"name":"%1$s_write"}]""".formatted(id, confirmEcho), config);
    }

    public static String manifest(String id, String version, String entrypoint, String tools, String config) {
        return """
                {"api":1,"id":"%s","version":"%s","name":"Test %s","description":"Test plugin %s",
                 "entrypoint":"%s","tools":%s,"config":%s}""".formatted(id, version, id, id, entrypoint, tools, config);
    }

    /** 编译 {@code sources}（全限定类名到源码），和清单一起打成 JAR。 */
    public static Path jar(Path directory, String manifest, Map<String, String> sources) {
        try {
            Path classes = compiled(sources);
            Path jar = Files.createTempFile(directory, "plugin", ".jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
                out.putNextEntry(new JarEntry(PluginManifest.PATH));
                out.write(manifest.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                try (var files = Files.walk(classes)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                        out.write(Files.readAllBytes(file));
                        out.closeEntry();
                    }
                }
            }
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@link #echoPlugin} 的 JAR。 */
    public static Path echoJar(Path directory, String id, String version, boolean confirmEcho, String config) {
        return jar(directory, echoManifest(id, version, confirmEcho, config),
                Map.of("test." + javaPackage(id) + ".Plugin", echoPlugin(id)));
    }

    /** 每组不同的源码在一次测试运行中只编译一次，输出到构建目录。 */
    private static synchronized Path compiled(Map<String, String> sources) throws IOException {
        Path classes = COMPILED.get(sources);
        if (classes == null) {
            classes = Files.createTempDirectory(Files.createDirectories(Path.of("target", "test-plugin-classes")), "classes");
            compile(classes, sources);
            COMPILED.put(Map.copyOf(sources), classes);
        }
        return classes;
    }

    private static void compile(Path classes, Map<String, String> sources) throws IOException {
        if (sources.isEmpty()) return;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> units = sources.entrySet().stream().<JavaFileObject>map(source ->
                new SimpleJavaFileObject(URI.create("string:///" + source.getKey().replace('.', '/') + ".java"),
                        JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return source.getValue();
                    }
                }).toList();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            // -parameters：Spring AI 用方法参数名生成工具参数的 Schema。
            List<String> options = List.of("-classpath", classPath(), "-parameters", "-proc:none", "-Xlint:none");
            if (!compiler.getTask(null, files, diagnostics, options, null, units).call()) {
                throw new AssertionError("Test plugin did not compile: " + diagnostics.getDiagnostics());
            }
        }
    }

    private static String classPath() {
        String surefire = System.getProperty("surefire.test.class.path");
        return surefire != null && !surefire.isBlank() ? surefire : System.getProperty("java.class.path");
    }

    private static String javaPackage(String id) {
        return id.replace('-', '_');
    }
}
