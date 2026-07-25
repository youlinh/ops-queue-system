package com.acme.opsqueue.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AutoAssignmentEngineTest {

    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID ALPHA = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID BETA = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID TOMORROW_SECOND =
            UUID.fromString("00000000-0000-0000-0000-000000000050");

    private final AutoAssignmentEngine engine = new AutoAssignmentEngine();

    static Stream<Arguments> priorityCases() {
        return Stream.of(
                arguments(
                        "08:30 uses second line and ignores threshold",
                        "2026-07-25T08:30+08:00[Asia/Shanghai]",
                        "2026-07-25T08:00:00Z",
                        9,
                        0,
                        AssignmentRule.DAY_SECOND,
                        SECOND),
                arguments(
                        "17:30 uses second line below threshold",
                        "2026-07-25T17:30+08:00[Asia/Shanghai]",
                        "2026-07-25T08:00:00Z",
                        2,
                        0,
                        AssignmentRule.AFTER_HOURS_SECOND,
                        SECOND),
                arguments(
                        "second line at three uses third line",
                        "2026-07-25T20:00+08:00[Asia/Shanghai]",
                        "2026-07-25T08:00:00Z",
                        3,
                        0,
                        AssignmentRule.AFTER_HOURS_THIRD,
                        THIRD),
                arguments(
                        "21:00 submission for same-day operation overrides both thresholds",
                        "2026-07-25T22:00+08:00[Asia/Shanghai]",
                        "2026-07-25T13:00:00Z",
                        8,
                        8,
                        AssignmentRule.LATE_SAME_DAY_SECOND,
                        SECOND));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("priorityCases")
    void appliesPriorityDecisionTable(
            String name,
            String operationStart,
            String submittedAt,
            int secondDailyCount,
            int thirdDailyCount,
            AssignmentRule expectedRule,
            UUID expectedAssignee) {
        AssignmentDecision decision = engine.assign(input(
                operationStart,
                submittedAt,
                metric(SECOND, secondDailyCount, 0, null),
                metric(THIRD, thirdDailyCount, 0, null),
                metric(ALPHA, 0, 0, null)));

        assertThat(decision.rule()).isEqualTo(expectedRule);
        assertThat(decision.assigneeId()).isEqualTo(expectedAssignee);
        assertThat(decision.explanation()).isNotBlank();
    }

    @Test
    void treats0829AsAfterHours() {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-25T08:29+08:00[Asia/Shanghai]",
                "2026-07-25T00:00:00Z",
                metric(SECOND, 2, 0, null),
                metric(THIRD, 0, 0, null)));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.AFTER_HOURS_SECOND);
        assertThat(decision.assigneeId()).isEqualTo(SECOND);
    }

    @Test
    void submissionAt2059DoesNotTriggerLateSameDayRule() {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-25T22:00+08:00[Asia/Shanghai]",
                "2026-07-25T12:59:59Z",
                metric(SECOND, 3, 0, null),
                metric(THIRD, 0, 0, null)));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.AFTER_HOURS_THIRD);
        assertThat(decision.assigneeId()).isEqualTo(THIRD);
    }

    @Test
    void lateSubmissionDoesNotOverrideThresholdForNextDayOperation() {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-26T22:00+08:00[Asia/Shanghai]",
                "2026-07-25T13:00:00Z",
                metric(SECOND, 3, 0, null),
                metric(THIRD, 0, 0, null)));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.AFTER_HOURS_THIRD);
        assertThat(decision.assigneeId()).isEqualTo(THIRD);
    }

    @Test
    void lateSameDayUnavailableSecondLineUsesThirdLineDespiteThreshold() {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-25T22:00+08:00[Asia/Shanghai]",
                "2026-07-25T13:00:00Z",
                unavailable(SECOND, 0),
                metric(THIRD, 9, 0, null),
                metric(ALPHA, 0, 0, null)));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.LATE_SAME_DAY_THIRD);
        assertThat(decision.assigneeId()).isEqualTo(THIRD);
    }

    @Test
    void lateSameDayUnavailableDutyPairEntersFairAllocation() {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-25T22:00+08:00[Asia/Shanghai]",
                "2026-07-25T13:00:00Z",
                unavailable(SECOND, 0),
                unavailable(THIRD, 0),
                metric(ALPHA, 1, 0, null),
                metric(BETA, 2, 0, null)));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.FAIR);
        assertThat(decision.assigneeId()).isEqualTo(ALPHA);
    }

    @Test
    void unavailableSecondLineFallsBackToThirdLineDuringDaytime() {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-25T10:00+08:00[Asia/Shanghai]",
                "2026-07-25T00:00:00Z",
                unavailable(SECOND, 0),
                metric(THIRD, 7, 0, null),
                metric(ALPHA, 0, 0, null)));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.DAY_THIRD);
        assertThat(decision.assigneeId()).isEqualTo(THIRD);
        assertThat(decision.candidates())
                .anySatisfy(snapshot -> {
                    assertThat(snapshot.userId()).isEqualTo(SECOND);
                    assertThat(snapshot.exclusionReason()).isEqualTo("OPERATION_DAY_UNAVAILABLE");
                });
    }

    @Test
    void unavailableDutyPairEntersFairAllocation() {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-25T10:00+08:00[Asia/Shanghai]",
                "2026-07-25T00:00:00Z",
                unavailable(SECOND, 0),
                unavailable(THIRD, 0),
                metric(ALPHA, 1, 10, null),
                metric(BETA, 2, 0, null)));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.FAIR);
        assertThat(decision.assigneeId()).isEqualTo(ALPHA);
        assertThat(decision.candidates()).extracting(CandidateSnapshot::userId).containsExactly(ALPHA, BETA);
    }

    @Test
    void afterHoursUnavailableSecondLineDirectlyChecksThirdLine() {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-25T20:00+08:00[Asia/Shanghai]",
                "2026-07-25T00:00:00Z",
                unavailable(SECOND, 0),
                metric(THIRD, 2, 0, null),
                metric(ALPHA, 0, 0, null)));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.AFTER_HOURS_THIRD);
        assertThat(decision.assigneeId()).isEqualTo(THIRD);
    }

    @Test
    void afterHoursUnavailableThirdLineFallsBackToFairAllocation() {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-25T20:00+08:00[Asia/Shanghai]",
                "2026-07-25T00:00:00Z",
                metric(SECOND, 3, 0, null),
                unavailable(THIRD, 0),
                metric(ALPHA, 0, 0, null)));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.FAIR);
        assertThat(decision.assigneeId()).isEqualTo(ALPHA);
    }

    @Test
    void fairPoolIncludesCurrentDutyPairEvenAtThreshold() {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-25T20:00+08:00[Asia/Shanghai]",
                "2026-07-25T00:00:00Z",
                metric(SECOND, 3, 0, null),
                metric(THIRD, 3, 100, null),
                metric(ALPHA, 4, 0, null)));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.FAIR);
        assertThat(decision.assigneeId()).isEqualTo(SECOND);
        assertThat(decision.candidates())
                .extracting(CandidateSnapshot::userId)
                .containsExactly(SECOND, THIRD, ALPHA);
    }

    @Test
    void fairPoolExcludesTomorrowDutyUsersFromSnapshots() {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-25T20:00+08:00[Asia/Shanghai]",
                "2026-07-25T00:00:00Z",
                metric(SECOND, 3, 0, null),
                metric(THIRD, 3, 0, null),
                nextDayDuty(TOMORROW_SECOND, 0),
                metric(ALPHA, 1, 0, null)));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.FAIR);
        assertThat(decision.candidates())
                .extracting(CandidateSnapshot::userId)
                .doesNotContain(TOMORROW_SECOND);
    }

    static Stream<Arguments> fairTieBreakerCases() {
        Instant recent = Instant.parse("2026-07-24T00:00:00Z");
        Instant old = Instant.parse("2026-07-01T00:00:00Z");
        return Stream.of(
                arguments(
                        "daily count wins before monthly minutes",
                        metric(ALPHA, 0, 500, recent),
                        metric(BETA, 1, 0, null),
                        ALPHA),
                arguments(
                        "monthly minutes wins before last assignment",
                        metric(ALPHA, 0, 10, recent),
                        metric(BETA, 0, 20, null),
                        ALPHA),
                arguments(
                        "null assignment wins before user ID",
                        metric(ALPHA, 0, 10, recent),
                        metric(BETA, 0, 10, null),
                        BETA),
                arguments(
                        "oldest assignment wins before user ID",
                        metric(ALPHA, 0, 10, recent),
                        metric(BETA, 0, 10, old),
                        BETA),
                arguments(
                        "user ID is the final stable tie breaker",
                        metric(ALPHA, 0, 10, old),
                        metric(BETA, 0, 10, old),
                        ALPHA));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fairTieBreakerCases")
    void ordersFairCandidatesByEveryTieBreaker(
            String name, CandidateMetric alpha, CandidateMetric beta, UUID expectedAssignee) {
        AssignmentDecision decision = engine.assign(input(
                "2026-07-25T20:00+08:00[Asia/Shanghai]",
                "2026-07-25T00:00:00Z",
                metric(SECOND, 3, 0, null),
                metric(THIRD, 3, 0, null),
                alpha,
                beta));

        assertThat(decision.rule()).isEqualTo(AssignmentRule.FAIR);
        assertThat(decision.assigneeId()).isEqualTo(expectedAssignee);
        assertThat(decision.candidates().getFirst().userId()).isEqualTo(expectedAssignee);
    }

    @Test
    void throwsWhenFairPoolHasNoEligibleCandidate() {
        AssignmentInput input = input(
                "2026-07-25T20:00+08:00[Asia/Shanghai]",
                "2026-07-25T00:00:00Z",
                unavailable(SECOND, 3),
                unavailable(THIRD, 3),
                nextDayDuty(ALPHA, 0));

        assertThatThrownBy(() -> engine.assign(input))
                .isInstanceOf(NoEligibleCandidateException.class)
                .hasMessageContaining("eligible");
    }

    @Test
    void inputAndDecisionCandidateCollectionsAreImmutableCopies() {
        List<CandidateMetric> mutableCandidates = new ArrayList<>(List.of(
                metric(SECOND, 0, 0, null), metric(THIRD, 0, 0, null)));
        AssignmentInput input = new AssignmentInput(
                Instant.parse("2026-07-25T00:00:00Z"),
                ZonedDateTime.parse("2026-07-25T10:00+08:00[Asia/Shanghai]"),
                new DutyPair(SECOND, THIRD),
                mutableCandidates);

        mutableCandidates.clear();
        assertThat(input.candidates()).hasSize(2);
        assertThatThrownBy(() -> input.candidates().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        AssignmentDecision decision = engine.assign(input);
        assertThatThrownBy(() -> decision.candidates().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsOperationStartOutsideBusinessZone() {
        assertThatThrownBy(() -> new AssignmentInput(
                        Instant.parse("2026-07-25T00:00:00Z"),
                        ZonedDateTime.parse("2026-07-25T10:00Z"),
                        new DutyPair(SECOND, THIRD),
                        List.of(metric(SECOND, 0, 0, null), metric(THIRD, 0, 0, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Asia/Shanghai");
    }

    private static AssignmentInput input(
            String operationStart, String submittedAt, CandidateMetric... candidates) {
        return new AssignmentInput(
                Instant.parse(submittedAt),
                ZonedDateTime.parse(operationStart),
                new DutyPair(SECOND, THIRD),
                List.of(candidates));
    }

    private static CandidateMetric metric(
            UUID userId,
            int dailyTaskCount,
            long monthlyActualMinutes,
            Instant lastAssignedAt) {
        return new CandidateMetric(
                userId,
                dailyTaskCount,
                monthlyActualMinutes,
                lastAssignedAt,
                true,
                false);
    }

    private static CandidateMetric unavailable(UUID userId, int dailyTaskCount) {
        return new CandidateMetric(userId, dailyTaskCount, 0, null, false, false);
    }

    private static CandidateMetric nextDayDuty(UUID userId, int dailyTaskCount) {
        return new CandidateMetric(userId, dailyTaskCount, 0, null, true, true);
    }
}
