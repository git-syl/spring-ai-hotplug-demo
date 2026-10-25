package com.example.agent.plugin;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 官方工具循环看到的插件工具。工具定义和元数据原样来自插件，不改名、不改元数据；每次调用只增加横切能力：
 * 计入运行实例的在途调用、以插件的类加载器作为线程上下文类加载器执行、整理异常、写不含参数和结果的审计日志。
 *
 * <p>官方 {@code DefaultToolCallingManager} 只把原因（cause）为非受检异常的 {@link ToolExecutionException} 变成工具结果，
 * 其它情况都会中断整个请求；而官方 {@code MethodToolCallback} 会把 {@code IOException} 这类受检异常原样包进去。
 * 所以这里把插件的每个异常都整理成那种形式，插件失败一律变成交给模型的工具结果；{@code OutOfMemoryError} 这类 Error 不吞掉。</p>
 */
final class PluginToolCallback implements ToolCallback {

    private static final Logger audit = LoggerFactory.getLogger(PluginToolCallback.class);

    private final PluginRuntime runtime;
    private final ToolCallback tool;

    PluginToolCallback(PluginRuntime runtime, ToolCallback tool) {
        this.runtime = runtime;
        this.tool = tool;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return tool.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return tool.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        if (!runtime.enterCall()) {
            throw new ToolExecutionException(getToolDefinition(),
                    new IllegalStateException("Plugin " + runtime.label() + " has stopped"));
        }
        long started = System.nanoTime();
        String outcome = "error";
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(runtime.classLoader());
        try {
            String result = tool.call(toolInput, toolContext);
            outcome = "success";
            return result;
        } catch (ToolExecutionException e) {
            outcome = "failure";
            throw e.getCause() instanceof Exception cause && !(cause instanceof RuntimeException)
                    ? new ToolExecutionException(e.getToolDefinition(), unchecked(cause)) : e;
        } catch (Exception e) {
            // 也能接住插件自己实现的 ToolCallback 偷偷抛出的受检异常。
            outcome = "failure";
            throw new ToolExecutionException(getToolDefinition(), unchecked(e));
        } finally {
            thread.setContextClassLoader(previous);
            runtime.exitCall();
            audit.info("plugin.call plugin={} version={} tool={} userId={} sessionId={} durationMs={} outcome={}",
                    runtime.manifest().id(), runtime.version(), getToolDefinition().name(),
                    identity(toolContext, "userId"), identity(toolContext, "sessionId"),
                    (System.nanoTime() - started) / 1_000_000, outcome);
        }
    }

    private static RuntimeException unchecked(Exception e) {
        if (e instanceof RuntimeException runtimeException) return runtimeException;
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        String message = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return new RuntimeException(message, e);
    }

    private static @Nullable Object identity(@Nullable ToolContext context, String key) {
        return context == null ? null : context.getContext().get(key);
    }
}
