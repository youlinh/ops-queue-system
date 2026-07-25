package com.acme.opsqueue.scheduling;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public record AssignmentInput(
        Instant submittedAt,
        ZonedDateTime operationStart,
        DutyPair dutyPair,
        Set<UUID> activeOperatorIds,
        List<CandidateMetric> candidates) {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public AssignmentInput {
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        Objects.requireNonNull(operationStart, "operationStart must not be null");
        Objects.requireNonNull(dutyPair, "dutyPair must not be null");
        activeOperatorIds = Set.copyOf(
                Objects.requireNonNull(activeOperatorIds, "activeOperatorIds must not be null"));
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));

        if (!operationStart.getZone().equals(BUSINESS_ZONE)) {
            throw new IllegalArgumentException("operationStart must use Asia/Shanghai");
        }

        Set<UUID> inactiveDutyUserIds = new TreeSet<>();
        if (!activeOperatorIds.contains(dutyPair.secondLineUserId())) {
            inactiveDutyUserIds.add(dutyPair.secondLineUserId());
        }
        if (!activeOperatorIds.contains(dutyPair.thirdLineUserId())) {
            inactiveDutyUserIds.add(dutyPair.thirdLineUserId());
        }
        if (!inactiveDutyUserIds.isEmpty()) {
            throw new MalformedAssignmentInputException(
                    "Current duty users must be active operators; non-active duty user IDs="
                            + inactiveDutyUserIds);
        }

        Set<UUID> userIds = new LinkedHashSet<>();
        for (CandidateMetric candidate : candidates) {
            Objects.requireNonNull(candidate, "candidates must not contain null");
            if (!userIds.add(candidate.userId())) {
                throw new MalformedAssignmentInputException(
                        "Candidate metrics contain duplicate user ID=" + candidate.userId());
            }
        }

        Set<UUID> missing = new TreeSet<>(activeOperatorIds);
        missing.removeAll(userIds);
        Set<UUID> extra = new TreeSet<>(userIds);
        extra.removeAll(activeOperatorIds);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new MalformedAssignmentInputException(
                    "Candidate metric IDs must exactly match activeOperatorIds; missing="
                            + missing
                            + ", extra="
                            + extra);
        }
    }
}
