package com.acme.opsqueue.assignment;

import java.util.UUID;

public record RedistributionItemResult(
        UUID taskId,
        String ticketNumber,
        boolean success,
        UUID previousAssigneeId,
        UUID assigneeId,
        boolean needsManualAttention,
        String error) {
    static RedistributionItemResult success(
            TaskAssignmentRecord task, UUID assigneeId) {
        return new RedistributionItemResult(
                task.id(), task.ticketNumber(), true, task.currentAssigneeId(),
                assigneeId, false, null);
    }

    static RedistributionItemResult failure(
            TaskAssignmentRecord task, String error, boolean needsManualAttention) {
        return new RedistributionItemResult(
                task.id(), task.ticketNumber(), false, task.currentAssigneeId(),
                task.currentAssigneeId(), needsManualAttention, error);
    }
}
