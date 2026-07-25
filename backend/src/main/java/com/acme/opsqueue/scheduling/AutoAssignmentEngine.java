package com.acme.opsqueue.scheduling;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class AutoAssignmentEngine {

    static final Comparator<CandidateMetric> FAIR_ORDER =
            Comparator.comparingInt(CandidateMetric::dailyTaskCount)
                    .thenComparingLong(CandidateMetric::monthlyActualMinutes)
                    .thenComparing(
                            CandidateMetric::lastAssignedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(CandidateMetric::userId);

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime DAY_START = LocalTime.of(8, 30);
    private static final LocalTime DAY_END = LocalTime.of(17, 30);
    private static final LocalTime LATE_SUBMISSION_START = LocalTime.of(21, 0);
    private static final int AFTER_HOURS_THRESHOLD = 3;
    private static final String OPERATION_DAY_UNAVAILABLE = "OPERATION_DAY_UNAVAILABLE";

    public AssignmentDecision assign(AssignmentInput input) {
        Objects.requireNonNull(input, "input must not be null");

        Map<UUID, CandidateMetric> candidatesById = input.candidates().stream()
                .collect(Collectors.toMap(
                        CandidateMetric::userId,
                        Function.identity(),
                        (first, duplicate) -> {
                            throw new IllegalArgumentException("duplicate candidate userId");
                        },
                        LinkedHashMap::new));
        CandidateMetric second = candidatesById.get(input.dutyPair().secondLineUserId());
        CandidateMetric third = candidatesById.get(input.dutyPair().thirdLineUserId());

        if (isLateSameDay(input)) {
            if (isAvailable(second)) {
                return priorityDecision(
                        AssignmentRule.LATE_SAME_DAY_SECOND,
                        second,
                        second,
                        third,
                        "Submission is at or after 21:00 for the operation day; "
                                + "second line is available and the daily threshold is ignored.");
            }
            if (isAvailable(third)) {
                return priorityDecision(
                        AssignmentRule.LATE_SAME_DAY_THIRD,
                        third,
                        second,
                        third,
                        "Submission is at or after 21:00 for the operation day; "
                                + "second line is unavailable, so third line is selected "
                                + "without applying the daily threshold.");
            }
            return fairDecision(
                    input,
                    "Late same-day duty users are unavailable; fair allocation applies.");
        }

        if (isDaytime(input.operationStart().toLocalTime())) {
            if (isAvailable(second)) {
                return priorityDecision(
                        AssignmentRule.DAY_SECOND,
                        second,
                        second,
                        third,
                        "Operation starts in [08:30, 17:30); "
                                + "second line is available and the daily threshold is ignored.");
            }
            if (isAvailable(third)) {
                return priorityDecision(
                        AssignmentRule.DAY_THIRD,
                        third,
                        second,
                        third,
                        "Operation starts in [08:30, 17:30); "
                                + "second line is unavailable, so third line is selected "
                                + "without applying the daily threshold.");
            }
            return fairDecision(input, "Daytime duty users are unavailable; fair allocation applies.");
        }

        if (isAvailable(second) && second.dailyTaskCount() < AFTER_HOURS_THRESHOLD) {
            return priorityDecision(
                    AssignmentRule.AFTER_HOURS_SECOND,
                    second,
                    second,
                    third,
                    "Operation starts outside [08:30, 17:30); "
                            + "second line is available with fewer than three operation-day tasks.");
        }
        if (isAvailable(third) && third.dailyTaskCount() < AFTER_HOURS_THRESHOLD) {
            return priorityDecision(
                    AssignmentRule.AFTER_HOURS_THIRD,
                    third,
                    second,
                    third,
                    "Operation starts outside [08:30, 17:30); "
                            + "second line is unavailable or at threshold, and third line "
                            + "is available with fewer than three operation-day tasks.");
        }
        return fairDecision(
                input,
                "After-hours duty users are unavailable or have at least three "
                        + "operation-day tasks; fair allocation applies.");
    }

    private static boolean isLateSameDay(AssignmentInput input) {
        ZonedDateTime submittedInBusinessZone = input.submittedAt().atZone(BUSINESS_ZONE);
        return !submittedInBusinessZone.toLocalTime().isBefore(LATE_SUBMISSION_START)
                && submittedInBusinessZone.toLocalDate().equals(input.operationStart().toLocalDate());
    }

    private static boolean isDaytime(LocalTime operationTime) {
        return !operationTime.isBefore(DAY_START) && operationTime.isBefore(DAY_END);
    }

    private static boolean isAvailable(CandidateMetric candidate) {
        return candidate != null && candidate.availableOnOperationDay();
    }

    private static AssignmentDecision priorityDecision(
            AssignmentRule rule,
            CandidateMetric assignee,
            CandidateMetric second,
            CandidateMetric third,
            String explanation) {
        List<CandidateSnapshot> snapshots = new ArrayList<>(2);
        addDutySnapshot(snapshots, second);
        if (third != null && (second == null || !third.userId().equals(second.userId()))) {
            addDutySnapshot(snapshots, third);
        }
        return new AssignmentDecision(rule, assignee.userId(), snapshots, explanation);
    }

    private static void addDutySnapshot(
            List<CandidateSnapshot> snapshots, CandidateMetric candidate) {
        if (candidate == null) {
            return;
        }
        snapshots.add(candidate.availableOnOperationDay()
                ? CandidateSnapshot.eligible(candidate)
                : CandidateSnapshot.excluded(candidate, OPERATION_DAY_UNAVAILABLE));
    }

    private static AssignmentDecision fairDecision(AssignmentInput input, String explanation) {
        Map<UUID, CandidateMetric> metricsByUserId = input.candidates().stream()
                .collect(Collectors.toMap(CandidateMetric::userId, Function.identity()));
        List<CandidateMetric> orderedCandidates = input.activeOperatorIds().stream()
                .map(metricsByUserId::get)
                .filter(CandidateMetric::availableOnOperationDay)
                .filter(candidate -> !candidate.nextDayDuty())
                .sorted(FAIR_ORDER)
                .toList();

        if (orderedCandidates.isEmpty()) {
            throw new NoEligibleCandidateException();
        }

        CandidateMetric assignee = orderedCandidates.getFirst();
        List<CandidateSnapshot> snapshots =
                orderedCandidates.stream().map(CandidateSnapshot::eligible).toList();
        return new AssignmentDecision(
                AssignmentRule.FAIR,
                assignee.userId(),
                snapshots,
                explanation
                        + " Eligible candidates are ordered by daily task count, monthly "
                        + "completed actual minutes, last assignment (null/oldest first), "
                        + "then user ID.");
    }
}
