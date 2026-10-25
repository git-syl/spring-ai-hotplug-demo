package com.example.agent.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * SSE 流里的一个事件，序列化为一行 {@code data:{...}}，没有值的字段不输出。
 *
 * <ul>
 *   <li>{@code token}：模型输出的一段文字，在 text 里；</li>
 *   <li>{@code tool_call}：模型发起的一次工具调用，id 是调用 ID，plugin 是提供工具的插件版本（宿主自带的工具为空）；</li>
 *   <li>{@code approval_request}：这次调用需要人工批准，id 是审批 ID，用它调用 {@code POST /approvals/{id}}；</li>
 *   <li>{@code tool_result}：调用结果（被拒绝时是“未执行”的说明），在 text 里；</li>
 *   <li>{@code heartbeat}：定期发出的心跳，客户端忽略即可；</li>
 *   <li>{@code finish} / {@code error}：流正常结束 / 出错结束，错误信息在 text 里。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(String type, @Nullable String id, @Nullable String tool, @Nullable String plugin,
                        @Nullable String arguments, @Nullable String text) {

    static ChatEvent token(String text) {
        return new ChatEvent("token", null, null, null, null, text);
    }

    static ChatEvent toolCall(String callId, String tool, @Nullable String plugin, String arguments) {
        return new ChatEvent("tool_call", callId, tool, plugin, arguments, null);
    }

    static ChatEvent approvalRequest(String approvalId, String tool, @Nullable String plugin, String arguments) {
        return new ChatEvent("approval_request", approvalId, tool, plugin, arguments, null);
    }

    static ChatEvent toolResult(String callId, String tool, @Nullable String plugin, String result) {
        return new ChatEvent("tool_result", callId, tool, plugin, null, result);
    }

    static ChatEvent heartbeat() {
        return new ChatEvent("heartbeat", null, null, null, null, null);
    }

    static ChatEvent finish() {
        return new ChatEvent("finish", null, null, null, null, null);
    }

    static ChatEvent error(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new ChatEvent("error", null, null, null, null, message);
    }
}
