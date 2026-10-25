package com.example.agent.plugin;

import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * 加载一个插件运行实例的 JAR。委派规则是父优先：宿主的 Spring、Spring AI、Jackson 等类总是胜过 JAR 里的同名副本，
 * 契约接口因此只有一份。{@code com.example.agent.} 下除契约包以外的类一律拒绝，插件只能依赖公开契约，
 * 宿主重构内部类不会破坏插件。这是工程纪律，不是安全边界。
 */
final class PluginClassLoader extends URLClassLoader {

    private static final String HOST_PACKAGE = "com.example.agent.";
    private static final String CONTRACT_PACKAGE = "com.example.agent.plugin.api.";

    static {
        registerAsParallelCapable();
    }

    PluginClassLoader(String name, Path jar, ClassLoader parent) {
        super(name, new URL[]{url(jar)}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(HOST_PACKAGE) && !name.startsWith(CONTRACT_PACKAGE)) {
            throw new ClassNotFoundException(name + " is internal to the host; plugins may only use " + CONTRACT_PACKAGE + "*");
        }
        return super.loadClass(name, resolve);
    }

    private static URL url(Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new UncheckedIOException(e);
        }
    }
}
