package com.example.agent.plugin;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallConfirmationPolicy;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

/**
 * 插件的审批规则，作为一个 {@link ToolCallConfirmationPolicy} bean 接入：Spring AI 的工具自动配置会把所有这类 bean，
 * 与工具元数据 {@code requiresConfirmation}、配置项 {@code spring.ai.tools.confirmation.tools} 用 or 组合进它创建的
 * {@code ToolCallingManager}。每次判断都实时读取插件状态：清单里的 {@code requiresConfirmation} 和管理员的
 * {@code confirmTools}，所以安装、切换插件之后不需要重建任何东西。
 */
@Component
public class PluginToolConfirmationPolicy implements ToolCallConfirmationPolicy {

    private static final Logger log = LoggerFactory.getLogger(PluginToolConfirmationPolicy.class);

    private final PluginManager plugins;

    public PluginToolConfirmationPolicy(PluginManager plugins) {
        this.plugins = plugins;
    }

    @Override
    public boolean requiresConfirmation(AssistantMessage.ToolCall toolCall, @Nullable ToolMetadata toolMetadata) {
        try {
            return plugins.requiresConfirmation(toolCall.name());
        } catch (RuntimeException e) {
            // 守卫无法判断时，宁可问人，也不放行。
            log.warn("plugin.confirmation.undecided tool={} error={}", toolCall.name(), e.getMessage());
            return true;
        }
    }
}
