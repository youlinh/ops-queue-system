package com.acme.opsqueue.task;

import com.acme.opsqueue.scheduling.AssignmentRule;
import java.util.UUID;

public record CreatedTask(
        UUID id,
        String ticketNumber,
        UUID assigneeId,
        String assigneeName,
        AssignmentRule assignmentRule) {
}
