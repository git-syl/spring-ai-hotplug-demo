package com.example.agent.plugin;

import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** 像应用里那样装配插件组件：使用临时的存储目录和一个假的环境变量表，不启动 Spring。 */
final class TestPluginHost implements AutoCloseable {

    final Path root;
    /** 代替环境变量，供插件配置里的 {@code ${NAME}} 引用。 */
    final Map<String, String> environment = new ConcurrentHashMap<>();
    final PluginStore store;
    final PluginManager manager;
    final PluginInstaller installer;

    TestPluginHost(Path root) {
        this(root, DataSize.ofMegabytes(50), Duration.ofMinutes(5));
    }

    TestPluginHost(Path root, DataSize maxJarSize, Duration retireTimeout) {
        this.root = root;
        PluginProperties properties = new PluginProperties(root, maxJarSize, retireTimeout);
        store = new PluginStore(properties);
        manager = new PluginManager(store, properties, environment::get);
        installer = new PluginInstaller(store, manager, properties);
    }

    PluginView install(Path jar) {
        try (InputStream upload = Files.newInputStream(jar)) {
            return installer.install(upload, null).plugin();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    PluginView installAndEnable(Path jar, String id, String version) {
        install(jar);
        return manager.enable(id, version);
    }

    /** 等到这个插件不再有退役中的实例。 */
    void awaitRetired(String id) {
        await(() -> manager.view(id).runtimes().stream().noneMatch(runtime -> runtime.state().equals("RETIRING")),
                "retiring runtimes of " + id + " to stop");
    }

    static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + what);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Override
    public void close() {
        manager.stopAll();
    }
}
