package com.acme.opsqueue.task;

import java.time.Instant;
import java.util.UUID;

public record TaskView(
        UUID id,
        String ticketNumber,
        TaskCategory category,
        String systemName,
        String processNumber,
        Instant operationStart,
        Instant operationEnd,
        UUID creatorId,
        UUID currentAssigneeId,
        String status,
        Instant calledAt,
        UUID calledByUserId,
        Integer actualMinutes,
        Instant completedAt,
        UUID completedByUserId,
        long version) {
}
