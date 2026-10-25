package com.example.agent.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallConfirmationPolicy;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用 Spring AI 自己的 ChatClient、ToolCallingAdvisor 和 ToolCallingManager 跑完整的流式工具循环，只把模型 IO 换成模拟：
 * 第一轮模型同时要求调用 Bash（需要审批，和 application.yaml 的配置一样）和插件工具 acme_read（不需要），拿到结果后回答 "done"。
 */
class InteractiveToolCallingManagerTest {

    private final List<String> ran = new CopyOnWriteArrayList<>();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final ChatModel model = mock(ChatModel.class);

    @BeforeEach
    void model() {
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            prompts.add(prompt);
            return Flux.just(prompt.getInstructions().getLast() instanceof ToolResponseMessage
                    ? new ChatResponse(List.of(new Generation(new AssistantMessage("done"))))
                    : new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(
                            new AssistantMessage.ToolCall("a", "function", "Bash", "{\"command\":\"echo hi\"}"),
                            new AssistantMessage.ToolCall("b", "function", "acme_read", "{}"))).build()))));
        });
    }

    @Test
    void theStreamAsksForApprovalAndRunsTheBatchOnceApproved() {
        ApprovalRegistry approvals = new ApprovalRegistry(Duration.ofSeconds(10));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        stream(approvals).subscribe(events::add);

        ChatEvent request = awaitEvent(events, "approval_request");
        assertThat(request.tool()).isEqualTo("Bash");
        assertThat(request.arguments()).isEqualTo("{\"command\":\"echo hi\"}");
        assertThat(ran).as("the whole batch waits for the decision").isEmpty();
        assertThat(approvals.decide(request.id(), true)).isTrue();

        awaitEvent(events, "finish");
        assertThat(events).extracting(ChatEvent::type).containsExactly(
                "tool_call", "tool_call", "approval_request", "tool_result", "tool_result", "token", "finish");
        assertThat(events).filteredOn(event -> event.type().equals("tool_call"))
                .extracting(ChatEvent::tool, ChatEvent::plugin)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Bash", null),
                        org.assertj.core.groups.Tuple.tuple("acme_read", "acme@1.0.0"));
        assertThat(events).filteredOn(event -> event.type().equals("tool_result")).extracting(ChatEvent::text)
                .containsExactly("result-Bash", "result-acme_read");
        assertThat(ran).containsExactly("Bash", "acme_read");
        assertThat(approvals.pending()).isEmpty();
    }

    @Test
    void aDeclinedCallDoesNotRunAndTheModelIsToldWhy() {
        ApprovalRegistry approvals = new ApprovalRegistry(Duration.ofSeconds(10));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        stream(approvals).subscribe(events::add);

        approvals.decide(awaitEvent(events, "approval_request").id(), false);

        awaitEvent(events, "finish");
        assertThat(ran).containsExactly("acme_read");
        ToolResponseMessage results = (ToolResponseMessage) prompts.getLast().getInstructions().getLast();
        assertThat(results.getResponses()).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("a", "b");
        assertThat(results.getResponses().getFirst().responseData()).contains("was NOT executed", "declined");
    }

    @Test
    void nobodyAnsweringInTimeIsARefusal() {
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        stream(new ApprovalRegistry(Duration.ofMillis(50))).subscribe(events::add);

        awaitEvent(events, "finish");
        assertThat(ran).containsExactly("acme_read");
        assertThat(events).filteredOn(event -> event.type().equals("tool_result")).first()
                .satisfies(result -> assertThat(result.text()).contains("nobody approved it in time"));
    }

    @Test
    void disconnectingWithdrawsTheApprovalAndRunsNothingOfTheBatch() throws Exception {
        ApprovalRegistry approvals = new ApprovalRegistry(Duration.ofSeconds(10));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        Disposable subscription = stream(approvals).subscribe(events::add);
        ChatEvent request = awaitEvent(events, "approval_request");

        subscription.dispose(); // 客户端断开

        await(() -> approvals.pending().isEmpty(), "the approval to be withdrawn");
        assertThat(approvals.decide(request.id(), true)).as("an approval after the request ended is refused").isFalse();
        Thread.sleep(200);
        assertThat(ran).as("neither the flagged call nor the rest of its batch runs").isEmpty();
    }

    @Test
    void theStreamKeepsBeatingWhileItWaitsForAPerson() {
        ApprovalRegistry approvals = new ApprovalRegistry(Duration.ofSeconds(10));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        stream(approvals, Duration.ofMillis(50)).subscribe(events::add);
        ChatEvent request = awaitEvent(events, "approval_request");

        // 等待审批期间没有别的输出；心跳让服务端能发现客户端已经断开。
        awaitEvent(events, "heartbeat");
        approvals.decide(request.id(), true);

        awaitEvent(events, "finish");
        assertThat(events.getLast().type()).isEqualTo("finish");
    }

    private Flux<ChatEvent> stream(ApprovalRegistry approvals) {
        return stream(approvals, Duration.ofMinutes(1));
    }

    private Flux<ChatEvent> stream(ApprovalRegistry approvals, Duration heartbeat) {
        ChatEvents events = new ChatEvents(heartbeat);
        var manager = new InteractiveToolCallingManager(official(), approvals, events,
                tool -> tool.startsWith("acme_") ? "acme@1.0.0" : null);
        Flux<ChatResponse> replies = ChatClient.create(model).prompt().user("test")
                .tools(callback("Bash"), callback("acme_read"))
                .advisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build())
                .stream()
                .chatResponse();
        return events.respond(replies).doFinally(signal -> manager.close());
    }

    /** 和 application.yaml 里 spring.ai.tools.confirmation.tools: [Bash] 同样的确认规则。 */
    private static ToolCallingManager official() {
        return ToolCallingManager.builder().toolCallConfirmationPolicy(ToolCallConfirmationPolicy.forTools("Bash")).build();
    }

    private ToolCallback callback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name)
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }

            @Override
            public String call(String input) {
                ran.add(name);
                return "result-" + name;
            }
        };
    }

    private static ChatEvent awaitEvent(List<ChatEvent> events, String type) {
        await(() -> events.stream().anyMatch(event -> event.type().equals(type)), "a " + type + " event");
        return events.stream().filter(event -> event.type().equals(type)).findFirst().orElseThrow();
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + what);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
