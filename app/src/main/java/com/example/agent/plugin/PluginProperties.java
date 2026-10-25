package com.example.agent.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

/**
 * {@code plugins.*} 配置。
 *
 * @param root          插件存储根目录：registry.json、各版本 JAR、插件数据目录和上传暂存区
 * @param maxJarSize    上传 JAR 的大小上限
 * @param retireTimeout 退役的运行实例等待租约与在途调用归零的上限，超时后强制停止
 */
@ConfigurationProperties("plugins")
public record PluginProperties(@DefaultValue("data/plugins") Path root,
                               @DefaultValue("50MB") DataSize maxJarSize,
                               @DefaultValue("5m") Duration retireTimeout) {

    public PluginProperties {
        Assert.notNull(root, "plugins.root is required");
        Assert.isTrue(maxJarSize != null && maxJarSize.toBytes() > 0, "plugins.max-jar-size must be positive");
        Assert.isTrue(retireTimeout != null && retireTimeout.isPositive(), "plugins.retire-timeout must be positive");
    }
}
