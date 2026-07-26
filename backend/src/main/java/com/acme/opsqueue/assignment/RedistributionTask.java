package com.acme.opsqueue.assignment;

import java.time.Instant;
import java.util.UUID;

public record RedistributionTask(
        UUID taskId,
        String ticketNumber,
        String category,
        String systemName,
        Instant operationStart,
        UUID currentAssigneeId) {
}
