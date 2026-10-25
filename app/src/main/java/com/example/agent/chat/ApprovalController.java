package com.example.agent.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 人工审批：查看等待中的工具调用，批准或拒绝其中一条。 */
@RestController
@RequestMapping(value = "/approvals", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApprovalController {

    private final ApprovalRegistry approvals;

    public ApprovalController(ApprovalRegistry approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<ApprovalRegistry.PendingApproval> pending() {
        return approvals.pending();
    }

    /** 200：决定已送达等待中的调用；404：没有这条待审批（ID 错误、已超时或已经决定过）。 */
    @PostMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Accepted> decide(@PathVariable String id, @RequestBody Decision decision) {
        boolean accepted = approvals.decide(id, decision.approve());
        return ResponseEntity.status(accepted ? HttpStatus.OK : HttpStatus.NOT_FOUND).body(new Accepted(accepted));
    }

    public record Decision(boolean approve) {}

    public record Accepted(boolean accepted) {}
}
