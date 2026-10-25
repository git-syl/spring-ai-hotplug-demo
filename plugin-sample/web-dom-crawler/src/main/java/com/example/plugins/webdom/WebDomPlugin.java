package com.example.plugins.webdom;

import com.example.agent.plugin.api.AgentPlugin;
import com.example.agent.plugin.api.PluginContext;

import java.time.Duration;
import java.util.Map;

/**
 * 清单 {@code entrypoint} 指向的入口类。超时、大小上限、是否允许访问内网都来自管理员配置；模型只能决定抓哪个页面。
 */
public final class WebDomPlugin implements AgentPlugin {

    @Override
    public void start(PluginContext context) {
        Map<String, String> config = context.config();
        PageFetcher fetcher = new PageFetcher(Duration.ofSeconds(positive(config, "timeoutSeconds")),
                positive(config, "maxBytes"), Boolean.parseBoolean(config.get("allowPrivateNetwork")));
        // 先登记关闭器，再注册工具：即使后面的步骤失败，HTTP 客户端也会被关闭。
        context.onStop(fetcher);
        context.registerTools(new WebDomTools(fetcher, Math.toIntExact(positive(config, "maxNodes"))));
    }

    private static long positive(Map<String, String> config, String key) {
        String value = config.get(key);
        try {
            long parsed = Long.parseLong(value.strip());
            if (parsed > 0) return parsed;
        } catch (RuntimeException ignored) {
            // 下面统一带上配置键的名字报错。
        }
        throw new IllegalArgumentException("config " + key + " must be a positive integer, not " + value);
    }
}
