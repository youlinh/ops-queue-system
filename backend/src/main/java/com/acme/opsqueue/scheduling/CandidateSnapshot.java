package com.acme.opsqueue.scheduling;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CandidateSnapshot(
        UUID userId,
        int dailyTaskCount,
        long monthlyActualMinutes,
        Instant lastAssignedAt,
        String exclusionReason) {

    public CandidateSnapshot {
        Objects.requireNonNull(userId, "userId must not be null");
    }

    static CandidateSnapshot eligible(CandidateMetric metric) {
        return from(metric, null);
    }

    static CandidateSnapshot excluded(CandidateMetric metric, String reason) {
        return from(metric, Objects.requireNonNull(reason, "reason must not be null"));
    }

    private static CandidateSnapshot from(CandidateMetric metric, String reason) {
        return new CandidateSnapshot(
                metric.userId(),
                metric.dailyTaskCount(),
                metric.monthlyActualMinutes(),
                metric.lastAssignedAt(),
                reason);
    }
}
