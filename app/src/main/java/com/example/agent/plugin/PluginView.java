package com.example.agent.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 管理接口返回的插件详情。name、description、tools 和 config 取自已启用版本的清单，没有启用版本时取最近安装的版本。
 *
 * @param state     RUNNING：有当前运行实例；DISABLED：没有启用的版本；FAILED：启用的版本没能运行（启动失败）
 * @param runtimes  正在运行和仍在退役的实例
 * @param lastError 最近一次启动失败的原因，下一次成功启动或停用时清空
 */
public record PluginView(String id, String name, String description, State state, @Nullable String enabledVersion,
                         List<VersionView> versions, List<ToolView> tools, List<ConfigView> config,
                         List<RuntimeView> runtimes, @Nullable String lastError) {

    public enum State { RUNNING, DISABLED, FAILED }

    public record VersionView(String version, String sha256, String installedAt) {}

    /** @param requiresConfirmation 清单、工具自身的元数据或 {@code confirmTools} 任一要求审批即为 true */
    public record ToolView(String name, boolean requiresConfirmation) {}

    /** @param value 管理员保存的原始值；密钥键显示为 {@code ******}；未设置时为 null */
    public record ConfigView(String key, String description, boolean required, boolean secret, @Nullable String value,
                             @JsonProperty("default") @Nullable String defaultValue) {}

    /** @param state RUNNING 或 RETIRING */
    public record RuntimeView(String version, String state, int leases, int inFlight, String startedAt) {}
}
