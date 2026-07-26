package com.acme.opsqueue.assignment;

import com.acme.opsqueue.audit.AuditService;
import com.acme.opsqueue.identity.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
public class AssignmentController {
    private final AssignmentService assignments;
    private final RedistributionService redistribution;
    private final Clock clock;
    private final AuditService audits;

    public AssignmentController(
            AssignmentService assignments,
            RedistributionService redistribution,
            Clock clock,
            AuditService audits) {
        this.assignments = assignments;
        this.redistribution = redistribution;
        this.clock = clock;
        this.audits = audits;
    }

    @PostMapping("/api/assignments/tasks/{taskId}/transfer")
    @Transactional
    public AssignmentResult transfer(
            @PathVariable UUID taskId,
            @AuthenticationPrincipal CurrentUser actor,
            @RequestBody AssignmentRequest request) {
        requireBody(request);
        Instant at = clock.instant();
        AssignmentResult result = assignments.transfer(
                taskId, actor.id(), request.targetId(), request.reason(),
                at);
        auditAssignment(actor.id(), "TASK_TRANSFERRED", result, at);
        return result;
    }

    @PostMapping("/api/assignments/tasks/{taskId}/adjust")
    @Transactional
    public AssignmentResult adjust(
            @PathVariable UUID taskId,
            @AuthenticationPrincipal CurrentUser leader,
            @RequestBody AssignmentRequest request) {
        requireBody(request);
        Instant at = clock.instant();
        AssignmentResult result = assignments.leaderAdjust(
                taskId, leader.id(), request.targetId(), request.reason(),
                at);
        auditAssignment(leader.id(), "TASK_LEADER_ADJUSTED", result, at);
        return result;
    }

    @GetMapping("/api/assignments/redistribution/preview")
    public List<RedistributionTask> preview(
            @RequestParam UUID operatorId,
            @RequestParam LocalDate date,
            @AuthenticationPrincipal CurrentUser leader) {
        return redistribution.previewRedistribution(
                operatorId, date, leader.id());
    }

    @PostMapping("/api/assignments/redistribution")
    @Transactional
    public RedistributionResult redistribute(
            @AuthenticationPrincipal CurrentUser leader,
            @RequestBody RedistributionRequest request) {
        requireBody(request);
        Instant at = clock.instant();
        RedistributionResult result = redistribution.redistribute(
                request.operatorId(),
                request.date(),
                leader.id(),
                request.reason(),
                at);
        long succeeded = result.items().stream().filter(RedistributionItemResult::success).count();
        audits.recordCurrentRequest(
                leader.id(), "REDISTRIBUTION_EXECUTED", "OPERATOR",
                result.sourceOperatorId(), Map.of(),
                Map.of(
                        "date", result.date().toString(),
                        "taskCount", result.items().size(),
                        "successCount", succeeded,
                        "failureCount", result.items().size() - succeeded),
                at);
        return result;
    }

    @PostMapping("/api/unavailability")
    @Transactional
    public UnavailabilityView setUnavailable(
            @AuthenticationPrincipal CurrentUser leader,
            @RequestBody UnavailabilityRequest request) {
        requireBody(request);
        Instant at = clock.instant();
        UnavailabilityView result = assignments.setUnavailable(
                request.operatorId(),
                request.date(),
                request.reason(),
                leader.id(),
                at);
        audits.recordCurrentRequest(
                leader.id(), "UNAVAILABILITY_CREATED", "OPERATOR", result.operatorId(),
                Map.of(), Map.of("date", result.date().toString()), at);
        return result;
    }

    @DeleteMapping("/api/unavailability/{operatorId}/{date}")
    @Transactional
    public ResponseEntity<Void> removeUnavailable(
            @PathVariable UUID operatorId,
            @PathVariable LocalDate date,
            @AuthenticationPrincipal CurrentUser leader) {
        assignments.removeUnavailable(operatorId, date, leader.id());
        audits.recordCurrentRequest(
                leader.id(), "UNAVAILABILITY_REMOVED", "OPERATOR", operatorId,
                Map.of("date", date.toString()), Map.of(), clock.instant());
        return ResponseEntity.noContent().build();
    }

    private void auditAssignment(
            UUID actorId, String action, AssignmentResult result, Instant at) {
        audits.recordCurrentRequest(
                actorId, action, "TASK", result.taskId(),
                Map.of("assigneeId", result.previousAssigneeId()),
                Map.of("assigneeId", result.assigneeId()), at);
    }

    private void requireBody(Object request) {
        if (request == null) {
            throw AssignmentValidationException.invalidRequest(
                    "Request body is required");
        }
    }

    public record AssignmentRequest(UUID targetId, String reason) {
    }

    public record RedistributionRequest(
            UUID operatorId, LocalDate date, String reason) {
    }

    public record UnavailabilityRequest(
            UUID operatorId, LocalDate date, String reason) {
    }
}
