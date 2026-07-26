package com.acme.opsqueue.assignment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record TaskAssignmentRecord(
        UUID id,
        String ticketNumber,
        LocalDate operationDate,
        Instant operationStart,
        UUID currentAssigneeId,
        String status,
        long version) {
}
