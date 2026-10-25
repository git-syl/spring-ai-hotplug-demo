package com.example.agent.plugin.api;

import java.nio.file.Path;
import java.util.Map;

/** 宿主交给一个插件运行实例的全部能力。 */
public interface PluginContext {

    String pluginId();

    String version();

    /**
     * 管理员设置的配置：每个 {@code ${NAME}} 已替换为同名环境变量，未设置的键补入清单默认值；
     * 既没有值也没有默认值的可选键不会出现。
     */
    Map<String, String> config();

    /** 本插件的私有数据目录，升级后保留。 */
    Path dataDirectory();

    /**
     * 注册工具：{@code ToolCallback}、{@code ToolCallbackProvider}，或带 {@code @Tool} 方法的普通对象。
     * 只能在 {@link AgentPlugin#start} 期间调用；注册的工具名集合必须与清单声明的完全一致。
     */
    void registerTools(Object... tools);

    /** 运行实例停止时，按登记的逆序关闭这个资源。 */
    void onStop(AutoCloseable resource);
}
