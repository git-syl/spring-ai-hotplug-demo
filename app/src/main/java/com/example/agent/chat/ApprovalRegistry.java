package com.example.agent.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 等待人工审批的工具调用。工具线程登记后原地等待决定；审批人通过 {@code GET /approvals} 查看、
 * {@code POST /approvals/{id}} 批准或拒绝。除了“批准”，其它结局（拒绝、超时、请求结束）都不会执行工具。
 */
@Component
public class ApprovalRegistry {

    /** WITHDRAWN：请求在有人决定之前就结束了（例如客户端断开），审批被撤回。 */
    public enum Outcome { APPROVED, DECLINED, TIMED_OUT, WITHDRAWN }

    /** 一次等待审批的工具调用，审批人据此决定。 */
    public record PendingApproval(String id, String tool, String arguments, String requestedAt) {}

    private static final Logger log = LoggerFactory.getLogger(ApprovalRegistry.class);

    private final Duration timeout;
    private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();

    @Autowired
    public ApprovalRegistry(ChatProperties properties) {
        this(properties.approvalTimeout());
    }

    ApprovalRegistry(Duration timeout) {
        this.timeout = timeout;
    }

    /** 登记一次需要审批的调用；等待从登记时开始计时。 */
    Waiting ask(AssistantMessage.ToolCall call) {
        PendingApproval approval = new PendingApproval(UUID.randomUUID().toString(), call.name(), call.arguments(),
                Instant.now().toString());
        Waiting entry = new Waiting(approval, System.nanoTime() + timeout.toNanos());
        waiting.put(approval.id(), entry);
        // 只记工具名：参数可能含敏感内容，审批人通过接口查看。
        log.info("approval.pending id={} tool={}", approval.id(), approval.tool());
        return entry;
    }

    public List<PendingApproval> pending() {
        return waiting.values().stream().map(Waiting::approval)
                .sorted(Comparator.comparing(PendingApproval::requestedAt)).toList();
    }

    /** @return false：没有这条待审批，例如 ID 写错、已超时或已经决定过 */
    public boolean decide(String id, boolean approve) {
        Waiting entry = waiting.get(id);
        boolean accepted = entry != null && entry.decision.complete(approve);
        log.info("approval.decided id={} approve={} accepted={}", id, approve, accepted);
        return accepted;
    }

    /** 一条登记中的审批；关闭后从注册表移除，之后的决定不再被接受。 */
    final class Waiting implements AutoCloseable {
        private final PendingApproval approval;
        private final long deadline;
        private final CompletableFuture<Boolean> decision = new CompletableFuture<>();

        private Waiting(PendingApproval approval, long deadline) {
            this.approval = approval;
            this.deadline = deadline;
        }

        PendingApproval approval() {
            return approval;
        }

        /** 在当前（工具）线程上等待决定，最多等到登记时算好的截止时间。 */
        Outcome await() throws InterruptedException {
            try {
                boolean approved = decision.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                return approved ? Outcome.APPROVED : Outcome.DECLINED;
            } catch (TimeoutException e) {
                return Outcome.TIMED_OUT;
            } catch (CancellationException e) {
                return Outcome.WITHDRAWN;
            } catch (ExecutionException e) {
                return Outcome.DECLINED;
            }
        }

        /** 从注册表移除；还没决定时撤回这条审批，之后送来的决定不再被接受。 */
        @Override
        public void close() {
            waiting.remove(approval.id(), this);
            decision.cancel(false);
        }
    }
}
