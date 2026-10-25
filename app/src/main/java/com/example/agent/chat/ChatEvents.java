package com.example.agent.chat;

import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * 一次流式对话的事件出口。模型输出的文字（模型的流）、工具循环里的旁路事件（工具调用、审批请求、工具结果，来自工具线程）
 * 和定期的心跳都经这里串行发出，合成同一个 SSE 流；模型的流结束或出错时，以 finish 或 error 收尾。
 *
 * <p>心跳的用处：等待审批时流里没有别的输出，服务端只有在写入失败时才知道客户端已经断开。心跳写入失败会取消订阅，
 * 进而撤回等待中的审批、释放插件租约，而不是一直挂到审批超时。</p>
 */
final class ChatEvents {

    private final Sinks.Many<ChatEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
    private final Duration heartbeat;

    ChatEvents(Duration heartbeat) {
        this.heartbeat = heartbeat;
    }

    /** 工具线程、模型线程和心跳都会调用，加锁保证事件一个一个地发出。 */
    synchronized void emit(ChatEvent event) {
        sink.tryEmitNext(event);
    }

    /** 订阅模型的流并返回完整的事件流；客户端断开（取消订阅）时，同时取消模型的流和心跳。 */
    Flux<ChatEvent> respond(Flux<ChatResponse> model) {
        return Flux.defer(() -> {
            Disposable subscription = model.subscribe(
                    response -> {
                        String text = text(response);
                        if (!text.isEmpty()) emit(ChatEvent.token(text));
                    },
                    error -> end(ChatEvent.error(error)),
                    () -> end(ChatEvent.finish()));
            Disposable beats = Flux.interval(heartbeat, heartbeat).subscribe(tick -> emit(ChatEvent.heartbeat()));
            return sink.asFlux().doFinally(signal -> {
                beats.dispose();
                subscription.dispose();
            });
        });
    }

    private synchronized void end(ChatEvent last) {
        sink.tryEmitNext(last);
        sink.tryEmitComplete();
    }

    private static String text(ChatResponse response) {
        if (response.getResult() == null) return "";
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }
}
