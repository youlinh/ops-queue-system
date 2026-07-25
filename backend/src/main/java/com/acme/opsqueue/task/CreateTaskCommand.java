package com.acme.opsqueue.task;

import java.time.Instant;

public record CreateTaskCommand(
        TaskCategory category,
        String systemName,
        int estimatedMinutes,
        String processNumber,
        Instant operationStart,
        Instant operationEnd) {
}
