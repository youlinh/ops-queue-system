package com.acme.opsqueue.scheduling;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CandidateMetric(
        UUID userId,
        int dailyTaskCount,
        long monthlyActualMinutes,
        Instant lastAssignedAt,
        boolean availableOnOperationDay,
        boolean nextDayDuty) {

    public CandidateMetric {
        Objects.requireNonNull(userId, "userId must not be null");
        if (dailyTaskCount < 0) {
            throw new IllegalArgumentException("dailyTaskCount must not be negative");
        }
        if (monthlyActualMinutes < 0) {
            throw new IllegalArgumentException("monthlyActualMinutes must not be negative");
        }
    }
}
