package com.acme.opsqueue.scheduling;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AssignmentInput(
        Instant submittedAt,
        ZonedDateTime operationStart,
        DutyPair dutyPair,
        List<CandidateMetric> candidates) {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public AssignmentInput {
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        Objects.requireNonNull(operationStart, "operationStart must not be null");
        Objects.requireNonNull(dutyPair, "dutyPair must not be null");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));

        if (!operationStart.getZone().equals(BUSINESS_ZONE)) {
            throw new IllegalArgumentException("operationStart must use Asia/Shanghai");
        }

        Set<UUID> userIds = new HashSet<>();
        for (CandidateMetric candidate : candidates) {
            Objects.requireNonNull(candidate, "candidates must not contain null");
            if (!userIds.add(candidate.userId())) {
                throw new IllegalArgumentException(
                        "candidates must contain exactly one metric per active operator");
            }
        }
    }
}
