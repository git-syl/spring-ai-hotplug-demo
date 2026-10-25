package com.example.agent.plugin;

import com.example.agent.plugin.api.AgentPlugin;
import com.example.agent.plugin.api.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个已启动的插件版本：它的类加载器、入口实例、包装后的工具和停止时要执行的关闭器，以及请求租约数和在途调用数。
 * RUNNING 时可以发放租约；退役（RETIRING）后只服务已经发出的租约，租约和在途调用都归零（或退役超时）后停止。
 */
final class PluginRuntime {

    enum State { STARTING, RUNNING, RETIRING, STOPPED, FAILED }

    private static final Logger log = LoggerFactory.getLogger(PluginRuntime.class);
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final PluginManifest manifest;
    private final PluginClassLoader classLoader;
    private final Instant startedAt = Instant.now();
    /** 最近登记的在最前，也就是关闭的顺序。由 this 保护。 */
    private final Deque<AutoCloseable> stopActions = new ArrayDeque<>();
    private List<PluginToolCallback> tools = List.of();
    private State state = State.STARTING;
    private int leases;
    private int inFlight;

    private PluginRuntime(PluginManifest manifest, PluginClassLoader classLoader) {
        this.manifest = manifest;
        this.classLoader = classLoader;
    }

    /**
     * 从已安装的 JAR 启动一个运行实例。任何一步失败，都会逆序关闭已登记的关闭器、丢弃已注册的工具、关闭类加载器，然后抛出异常。
     *
     * @param values 管理员保存的原始配置值
     * @throws PluginException {@code START_FAILED}，消息里是失败原因
     */
    static PluginRuntime start(PluginManifest manifest, Path jar, Map<String, String> values,
                               Function<String, String> environment, Path dataDirectory) {
        Map<String, String> config = resolveConfig(manifest, values, environment);
        PluginRuntime runtime = new PluginRuntime(manifest,
                new PluginClassLoader(manifest.label(), jar, PluginRuntime.class.getClassLoader()));
        Context context = runtime.new Context(config, dataDirectory);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(runtime.classLoader);
        try {
            Files.createDirectories(dataDirectory);
            // 第二个参数 true：初始化入口类。插件的 static 代码块到这里才第一次执行。
            Class<?> type = Class.forName(manifest.entrypoint(), true, runtime.classLoader);
            if (!AgentPlugin.class.isAssignableFrom(type)) {
                throw failure(manifest, "entrypoint " + type.getName() + " does not implement " + AgentPlugin.class.getName());
            }
            ((AgentPlugin) type.getConstructor().newInstance()).start(context);
            runtime.publish(context.registered());
            return runtime;
        } catch (Exception | LinkageError e) {
            // LinkageError 包括 static 块抛错时的 ExceptionInInitializerError 和缺类时的 NoClassDefFoundError。
            runtime.shutDown(State.FAILED);
            throw e instanceof PluginException failure ? failure : failure(manifest, describe(e));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * 管理员的配置值，其中每个 {@code ${NAME}} 替换为同名环境变量（从不读取 Spring 属性，配置借不走模型密钥之类的宿主秘密），
     * 空着的键补入清单默认值。只有清单声明过的键才会传给插件。
     */
    static Map<String, String> resolveConfig(PluginManifest manifest, Map<String, String> values,
                                             Function<String, String> environment) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (PluginManifest.ConfigKey key : manifest.config()) {
            String value = values.get(key.key());
            if (value != null && !value.isBlank()) {
                Matcher reference = REFERENCE.matcher(value);
                StringBuilder substituted = new StringBuilder();
                while (reference.find()) {
                    String variable = environment.apply(reference.group(1));
                    if (variable == null) {
                        throw failure(manifest, "config key '" + key.key() + "' references the unset environment variable "
                                + reference.group(1));
                    }
                    reference.appendReplacement(substituted, Matcher.quoteReplacement(variable));
                }
                resolved.put(key.key(), reference.appendTail(substituted).toString());
            } else if (key.defaultValue() != null) {
                resolved.put(key.key(), key.defaultValue());
            } else if (key.required()) {
                throw failure(manifest, "required config key '" + key.key() + "' has no value");
            }
        }
        return Collections.unmodifiableMap(resolved);
    }

    PluginManifest manifest() { return manifest; }

    String version() { return manifest.version(); }

    String label() { return manifest.label(); }

    ClassLoader classLoader() { return classLoader; }

    Instant startedAt() { return startedAt; }

    synchronized List<PluginToolCallback> tools() { return tools; }

    Optional<PluginToolCallback> tool(String name) {
        return tools().stream().filter(tool -> tool.getToolDefinition().name().equals(name)).findFirst();
    }

    synchronized State state() { return state; }

    synchronized int leases() { return leases; }

    synchronized int inFlight() { return inFlight; }

    /** 请求只能租用 RUNNING 的实例；退役的实例继续服务它已经发出的租约。 */
    synchronized boolean acquireLease() {
        if (state != State.RUNNING) return false;
        leases++;
        return true;
    }

    synchronized void releaseLease() {
        leases--;
        notifyAll();
    }

    /** 与租约分开计数：请求取消时租约立刻释放，而被中断的工具线程可能还在执行插件代码。 */
    synchronized boolean enterCall() {
        if (state != State.RUNNING && state != State.RETIRING) return false;
        inFlight++;
        return true;
    }

    synchronized void exitCall() {
        inFlight--;
        notifyAll();
    }

    synchronized void retire() {
        if (state == State.RUNNING) state = State.RETIRING;
    }

    /** @return 超时后仍有租约或在途调用时返回 false */
    synchronized boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while ((leases > 0 || inFlight > 0) && state != State.STOPPED) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }

    /** 按登记的逆序执行关闭器，再关闭类加载器。重复调用无副作用。 */
    void stop() {
        shutDown(State.STOPPED);
    }

    void addStopAction(AutoCloseable resource) {
        Objects.requireNonNull(resource, "resource");
        synchronized (this) {
            if (state != State.STOPPED && state != State.FAILED) {
                stopActions.push(resource);
                return;
            }
        }
        // 运行实例已经停止，之后不会再有人关闭这个资源，所以立即关闭。
        closeAll(List.of(resource));
    }

    private void shutDown(State terminal) {
        List<AutoCloseable> actions;
        synchronized (this) {
            if (state == State.STOPPED || state == State.FAILED) return;
            state = terminal;
            actions = List.copyOf(stopActions);
            stopActions.clear();
            notifyAll();
        }
        closeAll(actions);
        try {
            classLoader.close();
        } catch (IOException e) {
            log.warn("plugin.classloader.close-failed plugin={} error={}", label(), e.getMessage());
        }
        // Spring 的反射缓存和注解缓存都是软引用缓存，都会持有插件的工具类；不清空的话，类加载器只有在内存紧张时才能被回收。
        // 两个缓存缺一不可（见 PluginManagerTest），下次查询时它们会自动重新填充。
        ReflectionUtils.clearCache();
        AnnotationUtils.clearCache();
    }

    private void closeAll(List<AutoCloseable> actions) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(classLoader);
        try {
            for (AutoCloseable action : actions) {
                try {
                    action.close();
                } catch (Exception | LinkageError e) {
                    log.warn("plugin.stop-action.failed plugin={} error={}", label(), describe(e));
                }
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** 工具按清单顺序提供：反射不保证方法顺序，而模型每次都应看到同样的工具表。 */
    private synchronized void publish(List<ToolCallback> registered) {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback tool : registered) {
            String name = tool.getToolDefinition().name();
            if (byName.putIfAbsent(name, tool) != null) throw failure(manifest, "tool " + name + " was registered twice");
        }
        if (!byName.keySet().equals(manifest.toolNames())) {
            throw failure(manifest, "registered tools " + new TreeSet<>(byName.keySet())
                    + " differ from the manifest's " + new TreeSet<>(manifest.toolNames()));
        }
        tools = manifest.toolNames().stream().map(name -> new PluginToolCallback(this, byName.get(name))).toList();
        state = State.RUNNING;
    }

    private static PluginException failure(PluginManifest manifest, String reason) {
        return new PluginException(PluginException.Code.START_FAILED,
                "Plugin " + manifest.label() + " failed to start: " + reason);
    }

    /** 剥开反射与类初始化的包装异常，把真正的原因报告给管理员。 */
    private static String describe(Throwable e) {
        Throwable cause = e;
        while ((cause instanceof InvocationTargetException || cause instanceof ExceptionInInitializerError)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    /** 一个运行实例的上下文；只有在实例启动期间才能注册工具。 */
    private final class Context implements PluginContext {
        private final Map<String, String> config;
        private final Path dataDirectory;
        private final List<ToolCallback> registered = new ArrayList<>();

        Context(Map<String, String> config, Path dataDirectory) {
            this.config = config;
            this.dataDirectory = dataDirectory;
        }

        @Override public String pluginId() { return manifest.id(); }
        @Override public String version() { return manifest.version(); }
        @Override public Map<String, String> config() { return config; }
        @Override public Path dataDirectory() { return dataDirectory; }

        @Override
        public void registerTools(Object... tools) {
            synchronized (PluginRuntime.this) {
                if (state != State.STARTING) {
                    throw new IllegalStateException("Plugin tools can only be registered while the plugin starts");
                }
                for (Object tool : tools) {
                    Objects.requireNonNull(tool, "tool");
                    // 与 ChatClient#tools(Object...) 相同的分类规则。
                    if (tool instanceof ToolCallback callback) registered.add(callback);
                    else if (tool instanceof ToolCallbackProvider provider) registered.addAll(Arrays.asList(provider.getToolCallbacks()));
                    else registered.addAll(Arrays.asList(ToolCallbacks.from(tool)));
                }
            }
        }

        @Override
        public void onStop(AutoCloseable resource) {
            addStopAction(resource);
        }

        List<ToolCallback> registered() {
            synchronized (PluginRuntime.this) {
                return List.copyOf(registered);
            }
        }
    }
}
