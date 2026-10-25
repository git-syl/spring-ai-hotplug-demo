package com.example.agent.chat;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallConfirmationRequiredException;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 一次流式对话的工具执行器：装饰 Spring AI 自动配置的 {@link ToolCallingManager}，执行仍由官方完成，它只多做两件事。
 *
 * <ol>
 *   <li>把每批工具调用和结果作为 {@code tool_call}、{@code tool_result} 事件发给客户端；</li>
 *   <li>在官方抛出“需要确认”暂停（{@link ToolCallConfirmationRequiredException}，此时整批都没有执行）的地方就地请人决定：
 *       为每个被确认策略标记的调用发出 {@code approval_request} 并在工具线程上等待，没被标记的直接放行，
 *       再把整批决定交给官方的三参数 {@code executeToolCalls}——被拒绝的调用不执行，模型收到“未执行”的结果，循环继续。</li>
 * </ol>
 *
 * <p>阻塞等待符合官方循环的约定：流式的 ToolCallingAdvisor 在 boundedElastic 线程上执行工具。</p>
 */
final class InteractiveToolCallingManager implements ToolCallingManager {

    private static final int MAX_RESULT_LENGTH = 2_000;

    private final ToolCallingManager delegate;
    private final ApprovalRegistry approvals;
    private final ChatEvents events;
    private final Function<String, @Nullable String> pluginOf;
    /** 正在等待决定的审批；请求结束时由 {@link #close} 撤回。 */
    private final Set<ApprovalRegistry.Waiting> open = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** @param pluginOf 工具名对应的插件版本，例如 {@code webdom@1.0.0}；宿主自带的工具返回 null */
    InteractiveToolCallingManager(ToolCallingManager delegate, ApprovalRegistry approvals, ChatEvents events,
                                  Function<String, @Nullable String> pluginOf) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.events = Objects.requireNonNull(events, "events");
        this.pluginOf = Objects.requireNonNull(pluginOf, "pluginOf");
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse reply) {
        AssistantMessage batch = toolCallBatch(reply);
        for (AssistantMessage.ToolCall call : batch.getToolCalls()) {
            events.emit(ChatEvent.toolCall(call.id(), call.name(), pluginOf.apply(call.name()), call.arguments()));
        }
        ToolExecutionResult result;
        try {
            result = delegate.executeToolCalls(prompt, reply);
        } catch (ToolCallConfirmationRequiredException pause) {
            result = delegate.executeToolCalls(prompt, reply, decide(batch, pause));
        }
        emitResults(result);
        return result;
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse reply, ToolCallDecisions decisions) {
        ToolExecutionResult result = delegate.executeToolCalls(prompt, reply, decisions);
        emitResults(result);
        return result;
    }

    /** 请求结束（完成、出错或客户端断开）：撤回所有还在等待的审批，这批调用一个都不执行——不在无人等待时执行工具。 */
    void close() {
        closed = true;
        open.forEach(ApprovalRegistry.Waiting::close);
    }

    private ToolCallDecisions decide(AssistantMessage batch, ToolCallConfirmationRequiredException pause) {
        List<String> keys = ToolCallDecisions.toolCallKeys(batch);
        Map<String, ApprovalRegistry.Waiting> asked = new LinkedHashMap<>();
        try {
            // 先登记并公布这批里所有要审批的调用，审批人能一次看到全部，然后逐个等待决定。
            for (int i = 0; i < keys.size(); i++) {
                if (!pause.getToolCallIds().contains(keys.get(i))) continue;
                AssistantMessage.ToolCall call = batch.getToolCalls().get(i);
                ApprovalRegistry.Waiting waiting = approvals.ask(call);
                open.add(waiting);
                asked.put(keys.get(i), waiting);
                if (closed) waiting.close();
                events.emit(ChatEvent.approvalRequest(waiting.approval().id(), call.name(), pluginOf.apply(call.name()),
                        call.arguments()));
            }
            List<ToolCallDecision> decisions = new ArrayList<>(keys.size());
            for (String key : keys) {
                ApprovalRegistry.Waiting waiting = asked.get(key);
                if (waiting == null) {
                    // 官方 manager 要求整批每个调用都有决定；不需要审批的直接放行。
                    decisions.add(ToolCallDecision.allow(key));
                    continue;
                }
                ApprovalRegistry.Outcome outcome = waiting.await();
                // 请求已经结束（例如客户端断开）：暂停原样交回，整批都不执行，包括不需要审批的调用。
                if (outcome == ApprovalRegistry.Outcome.WITHDRAWN) throw pause;
                decisions.add(decision(key, waiting.approval().tool(), outcome));
            }
            return new ToolCallDecisions(decisions);
        } catch (InterruptedException requestEnded) {
            // 没有人做出决定，暂停依旧成立：官方循环结束这一轮，整批都不执行。
            Thread.currentThread().interrupt();
            throw pause;
        } finally {
            asked.values().forEach(waiting -> {
                waiting.close();
                open.remove(waiting);
            });
        }
    }

    private static ToolCallDecision decision(String key, String tool, ApprovalRegistry.Outcome outcome) {
        return switch (outcome) {
            case APPROVED -> ToolCallDecision.allow(key);
            case DECLINED -> ToolCallDecision.deny(key, notExecuted(tool, "the user declined to approve it"));
            case TIMED_OUT, WITHDRAWN -> ToolCallDecision.deny(key, notExecuted(tool, "nobody approved it in time"));
        };
    }

    /** 拒绝的理由作为这次调用的结果交给模型，让它如实告诉用户，而不是换个说法再调一次。 */
    private static String notExecuted(String tool, String reason) {
        return "The tool '" + tool + "' was NOT executed: " + reason + ". Do not call '" + tool
                + "' again for this request; tell the user that the action was not performed.";
    }

    private void emitResults(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        if (history.isEmpty() || !(history.getLast() instanceof ToolResponseMessage responses)) return;
        for (ToolResponseMessage.ToolResponse response : responses.getResponses()) {
            String data = response.responseData();
            events.emit(ChatEvent.toolResult(response.id(), response.name(), pluginOf.apply(response.name()),
                    data.length() <= MAX_RESULT_LENGTH ? data : data.substring(0, MAX_RESULT_LENGTH) + "…(truncated)"));
        }
    }

    private static AssistantMessage toolCallBatch(ChatResponse reply) {
        return reply.getResults().stream().map(Generation::getOutput).filter(AssistantMessage::hasToolCalls)
                .findFirst().orElseThrow(() -> new IllegalStateException("The reply has no tool calls"));
    }
}
