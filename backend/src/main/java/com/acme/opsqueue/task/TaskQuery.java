package com.acme.opsqueue.task;

import java.time.LocalDate;
import java.util.UUID;

public record TaskQuery(
        LocalDate operationDate,
        TaskCategory category,
        String systemName,
        String status,
        UUID creatorId,
        UUID assigneeId,
        int page,
        int size,
        String sort) {
}
