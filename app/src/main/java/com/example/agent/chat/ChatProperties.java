package com.example.agent.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

import java.nio.file.Path;
import java.time.Duration;

/**
 * {@code chat.*} 配置。
 *
 * @param workspace         Bash 工具执行命令时的工作目录
 * @param approvalTimeout   需要审批的工具调用最多等待多久，超时按“不批准”处理
 * @param heartbeatInterval SSE 心跳间隔。等待审批时流里没有别的输出，服务端靠心跳写入失败才能发现客户端已经断开，
 *                          进而撤回审批、释放插件租约
 */
@ConfigurationProperties("chat")
public record ChatProperties(@DefaultValue("data/workspace") Path workspace,
                             @DefaultValue("2m") Duration approvalTimeout,
                             @DefaultValue("15s") Duration heartbeatInterval) {

    public ChatProperties {
        Assert.notNull(workspace, "chat.workspace is required");
        Assert.isTrue(approvalTimeout != null && approvalTimeout.isPositive(), "chat.approval-timeout must be positive");
        Assert.isTrue(heartbeatInterval != null && heartbeatInterval.isPositive(), "chat.heartbeat-interval must be positive");
    }
}
