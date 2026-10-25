package com.example.agent.plugin;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次对话请求使用的插件运行实例及其工具。请求结束（成功、失败或取消）时关闭，每个实例的租约只释放一次。
 * 整个请求期间版本固定：即使管理员中途切换了版本，这次请求也一直使用它开始时租到的实例。
 */
public final class PluginLease implements AutoCloseable {

    private static final PluginLease NONE = new PluginLease(List.of());

    private final List<PluginRuntime> runtimes;
    private final ToolCallback[] callbacks;
    private final AtomicBoolean closed = new AtomicBoolean();

    PluginLease(List<PluginRuntime> runtimes) {
        this.runtimes = List.copyOf(runtimes);
        this.callbacks = this.runtimes.stream().flatMap(runtime -> runtime.tools().stream()).toArray(ToolCallback[]::new);
    }

    /** 不使用插件的请求。 */
    public static PluginLease none() {
        return NONE;
    }

    public ToolCallback[] callbacks() {
        return callbacks.clone();
    }

    /** 提供这个工具的插件版本，例如 {@code webdom@1.0.0}；不是本租约的插件工具时为 null。 */
    public @Nullable String pluginOf(String toolName) {
        return runtimes.stream().filter(runtime -> runtime.manifest().declaresTool(toolName))
                .map(PluginRuntime::label).findFirst().orElse(null);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) runtimes.forEach(PluginRuntime::releaseLease);
    }
}
