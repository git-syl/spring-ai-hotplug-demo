package com.example.agent.plugin.api;

/**
 * 插件入口，类名写在 {@code META-INF/agent-plugin.json} 的 {@code entrypoint} 中。
 * 宿主为每个运行实例用公开的无参构造器创建一次入口对象，然后调用 {@link #start}。
 */
public interface AgentPlugin {

    /**
     * 注册工具、申请资源。抛出异常即启动失败：已注册的工具全部丢弃，经 {@link PluginContext#onStop} 登记的资源逆序关闭。
     */
    void start(PluginContext context) throws Exception;
}
