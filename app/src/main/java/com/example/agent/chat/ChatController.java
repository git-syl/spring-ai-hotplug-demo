package com.example.agent.chat;

import com.example.agent.plugin.PluginLease;
import com.example.agent.plugin.PluginManager;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 流式对话 {@code POST /chat/stream}（SSE）。每次对话默认带上 Bash 工具（{@link ShellTools}），请求的 {@code plugins}
 * 里列出的插件，工具也一起交给 Spring AI 官方工具循环。插件在建立 SSE 前校验，订阅时租用，流结束（完成、出错或断开）时释放。
 */
@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final ToolCallingManager toolCallingManager;
    private final PluginManager plugins;
    private final ApprovalRegistry approvals;
    private final ChatProperties properties;

    /**
     * @param toolCallingManager Spring AI 自动配置的工具执行器：它的确认策略已经用 or 组合了工具元数据、
     *                           {@code spring.ai.tools.confirmation.tools}（这里是 Bash）和所有
     *                           {@code ToolCallConfirmationPolicy} bean，其中就包括插件的审批规则
     */
    public ChatController(ChatClient.Builder chatClient, ToolCallingManager toolCallingManager, ShellTools shellTools,
                          PluginManager plugins, ApprovalRegistry approvals, ChatProperties properties) {
        // 每次对话默认带上 Bash、BashOutput 和 KillShell。它们的名字里没有下划线，
        // 而插件工具名一定是 "<插件ID>_..."，两者不可能重名。
        this.chatClient = chatClient.defaultTools(shellTools).build();
        this.toolCallingManager = toolCallingManager;
        this.plugins = plugins;
        this.approvals = approvals;
        this.properties = properties;
    }

    /**
     * @param plugins 本次对话启用的插件 ID；没有列出的插件，它的工具不会出现在模型的工具表里
     * @param userId  写进 ToolContext 和审计日志，只用于审计和分区，不构成授权
     */
    public record ChatRequest(@Nullable String message, @Nullable List<String> plugins,
                              @Nullable String userId, @Nullable String sessionId) {}

    @PostMapping(value = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatEvent> stream(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        // 未知、未运行或重复的插件在建立 SSE 之前就以 HTTP 400 拒绝。
        List<String> pluginIds = plugins.check(request.plugins());
        // ToolContext 只给工具看，不会进入发给模型的 JSON Schema。
        Map<String, Object> toolContext = Map.of("userId", textOr(request.userId(), "anonymous"),
                "sessionId", textOr(request.sessionId(), UUID.randomUUID().toString()));
        return Flux.using(() -> plugins.acquire(pluginIds), lease -> {
                    ChatEvents events = new ChatEvents(properties.heartbeatInterval());
                    var manager = new InteractiveToolCallingManager(toolCallingManager, approvals, events, lease::pluginOf);
                    Flux<ChatResponse> model = chatClient.prompt()
                            .user(request.message())
                            // 在默认的 Bash 工具之外，加上本次请求所选插件的工具（没有选插件时为空）。
                            .tools(List.of(lease.callbacks()))
                            .toolContext(toolContext)
                            // 每条链只允许一个工具 advisor：放入我们自己的，ChatClient 就不再自动注册默认的那个。
                            .advisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build())
                            .stream()
                            .chatResponse();
                    return events.respond(model).doFinally(signal -> manager.close());
                }, PluginLease::close)
                // 例如插件恰好在校验之后被停用：SSE 已经建立，以一条 error 事件结束。
                .onErrorResume(error -> Flux.just(ChatEvent.error(error)));
    }

    private static String textOr(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
