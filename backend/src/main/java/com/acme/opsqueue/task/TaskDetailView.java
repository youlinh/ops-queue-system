package com.acme.opsqueue.task;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TaskDetailView(
        UUID id,
        String ticketNumber,
        TaskCategory category,
        String systemName,
        String processNumber,
        Instant operationStart,
        Instant operationEnd,
        UUID creatorId,
        String creatorName,
        UUID currentAssigneeId,
        String currentAssigneeName,
        String status,
        int estimatedMinutes,
        Integer actualMinutes,
        String assignmentRule,
        boolean canCall,
        boolean canComplete,
        boolean canTransfer,
        boolean needsManualAttention,
        Instant createdAt,
        Instant calledAt,
        UUID calledByUserId,
        Instant completedAt,
        UUID completedByUserId,
        long version,
        List<AssignmentTimelineEntry> assignmentTimeline) {

    public record AssignmentTimelineEntry(
            String assignmentType,
            UUID oldAssigneeId,
            String oldAssigneeName,
            UUID newAssigneeId,
            String newAssigneeName,
            String assignmentRule,
            String reason,
            UUID actorId,
            String actorName,
            Instant assignedAt) {
    }
}
