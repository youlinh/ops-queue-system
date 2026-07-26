package com.acme.opsqueue.assignment;

import com.acme.opsqueue.identity.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
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

@RestController
public class AssignmentController {
    private final AssignmentService assignments;
    private final RedistributionService redistribution;
    private final Clock clock;

    public AssignmentController(
            AssignmentService assignments,
            RedistributionService redistribution,
            Clock clock) {
        this.assignments = assignments;
        this.redistribution = redistribution;
        this.clock = clock;
    }

    @PostMapping("/api/assignments/tasks/{taskId}/transfer")
    public AssignmentResult transfer(
            @PathVariable UUID taskId,
            @AuthenticationPrincipal CurrentUser actor,
            @RequestBody AssignmentRequest request) {
        requireBody(request);
        return assignments.transfer(
                taskId, actor.id(), request.targetId(), request.reason(),
                clock.instant());
    }

    @PostMapping("/api/assignments/tasks/{taskId}/adjust")
    public AssignmentResult adjust(
            @PathVariable UUID taskId,
            @AuthenticationPrincipal CurrentUser leader,
            @RequestBody AssignmentRequest request) {
        requireBody(request);
        return assignments.leaderAdjust(
                taskId, leader.id(), request.targetId(), request.reason(),
                clock.instant());
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
    public RedistributionResult redistribute(
            @AuthenticationPrincipal CurrentUser leader,
            @RequestBody RedistributionRequest request) {
        requireBody(request);
        return redistribution.redistribute(
                request.operatorId(),
                request.date(),
                leader.id(),
                request.reason(),
                clock.instant());
    }

    @PostMapping("/api/unavailability")
    public UnavailabilityView setUnavailable(
            @AuthenticationPrincipal CurrentUser leader,
            @RequestBody UnavailabilityRequest request) {
        requireBody(request);
        return assignments.setUnavailable(
                request.operatorId(),
                request.date(),
                request.reason(),
                leader.id(),
                clock.instant());
    }

    @DeleteMapping("/api/unavailability/{operatorId}/{date}")
    public ResponseEntity<Void> removeUnavailable(
            @PathVariable UUID operatorId,
            @PathVariable LocalDate date,
            @AuthenticationPrincipal CurrentUser leader) {
        assignments.removeUnavailable(operatorId, date, leader.id());
        return ResponseEntity.noContent().build();
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
