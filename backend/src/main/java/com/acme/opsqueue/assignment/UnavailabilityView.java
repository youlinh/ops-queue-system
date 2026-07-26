package com.acme.opsqueue.assignment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record UnavailabilityView(
        UUID operatorId,
        LocalDate date,
        String reason,
        UUID createdByUserId,
        Instant updatedAt) {
}
