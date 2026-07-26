package com.acme.opsqueue.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.opsqueue.OpsQueueApplication;
import com.acme.opsqueue.identity.CurrentUser;
import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import com.acme.opsqueue.support.MySqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = OpsQueueApplication.class)
@ActiveProfiles("test")
class ReportingServiceTest extends MySqlIntegrationTest {
    @Autowired private ReportingService service;
    @Autowired private UserAccountRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcTemplate jdbc;

    private UserAccount operator;
    private UserAccount otherOperator;
    private UserAccount leader;
    private UserAccount developer;

    @BeforeEach
    void resetFixture() {
        jdbc.execute("TRUNCATE TABLE audit_logs");
        jdbc.update("DELETE FROM notification_events");
        jdbc.update("DELETE FROM assignment_histories");
        jdbc.update("DELETE FROM tasks");
        users.findAll().stream()
                .filter(account -> !account.username().equals("test-bootstrap-leader"))
                .forEach(users::delete);
        operator = account("report-operator", Set.of(RoleName.OPERATOR));
        otherOperator = account("report-other", Set.of(RoleName.OPERATOR));
        leader = account("report-leader", Set.of(RoleName.LEADER));
        developer = account("report-developer", Set.of(RoleName.DEVELOPER));
    }

    @Test
    void monthlyDurationUsesOperationMonthAndFinalAssigneeAcrossMonthEnd() {
        seedTask(operator.id(), "COMPLETED", 60,
                Instant.parse("2026-07-31T15:00:00Z"),
                Instant.parse("2026-07-31T15:00:00Z"),
                Instant.parse("2026-07-31T17:00:00Z"));

        MonthlyOperatorReport july =
                service.monthly(YearMonth.of(2026, 7), operator.id(), current(operator));
        MonthlyOperatorReport august =
                service.monthly(YearMonth.of(2026, 8), operator.id(), current(operator));

        assertThat(july.totalTaskCount()).isEqualTo(1);
        assertThat(july.completedActualMinutes()).isEqualTo(120);
        assertThat(august.totalTaskCount()).isZero();
        assertThat(august.completedActualMinutes()).isZero();
    }

    @Test
    void pendingAndInProgressTasksContributeZeroActualMinutes() {
        seedTask(operator.id(), "PENDING", 30,
                Instant.parse("2026-07-25T01:00:00Z"), null, null);
        seedTask(operator.id(), "IN_PROGRESS", 45,
                Instant.parse("2026-07-25T02:00:00Z"),
                Instant.parse("2026-07-25T03:00:00Z"), null);

        MonthlyOperatorReport report =
                service.monthly(YearMonth.of(2026, 7), operator.id(), current(operator));

        assertThat(report.totalTaskCount()).isEqualTo(2);
        assertThat(report.pendingCount()).isEqualTo(1);
        assertThat(report.inProgressCount()).isEqualTo(1);
        assertThat(report.completedActualMinutes()).isZero();
    }

    @Test
    void completedTaskStillContributesToOperationDayCountsAndActualMinutes() {
        seedTask(operator.id(), "COMPLETED", 90,
                Instant.parse("2026-07-25T10:00:00Z"),
                Instant.parse("2026-07-26T00:00:00Z"),
                Instant.parse("2026-07-26T00:30:00Z"));

        DailyOperatorReport report = service.daily(
                LocalDate.of(2026, 7, 25), operator.id(), current(operator));

        assertThat(report.totalTaskCount()).isEqualTo(1);
        assertThat(report.completedCount()).isEqualTo(1);
        assertThat(report.estimatedMinutes()).isEqualTo(90);
        assertThat(report.completedActualMinutes()).isEqualTo(30);
    }

    @Test
    void validOperatorWithoutTasksReceivesZeroProjection() {
        DailyOperatorReport report = service.daily(
                LocalDate.of(2026, 7, 25), operator.id(), current(operator));

        assertThat(report.totalTaskCount()).isZero();
        assertThat(report.estimatedMinutes()).isZero();
        assertThat(report.completedActualMinutes()).isZero();
    }

    @Test
    void operatorCanOnlyRequestSelfWhileLeaderCanRequestAnotherOperator() {
        assertThatThrownBy(() -> service.daily(
                LocalDate.of(2026, 7, 25), otherOperator.id(), current(operator)))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(service.daily(
                LocalDate.of(2026, 7, 25), otherOperator.id(), current(leader)).operatorId())
                .isEqualTo(otherOperator.id());
    }

    @Test
    void leaderScopeWinsForMultiRoleAccountAndInvalidTargetsAreRejected() {
        UserAccount leaderOperator =
                account("report-leader-operator", Set.of(RoleName.LEADER, RoleName.OPERATOR));

        assertThat(service.monthly(
                YearMonth.of(2026, 7), otherOperator.id(), current(leaderOperator)).operatorId())
                .isEqualTo(otherOperator.id());
        assertThatThrownBy(() -> service.monthly(
                YearMonth.of(2026, 7), developer.id(), current(leader)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void seedTask(
            UUID assigneeId,
            String status,
            int estimate,
            Instant operationStart,
            Instant actualStart,
            Instant actualEnd) {
        UUID id = UUID.randomUUID();
        LocalDate operationDate =
                operationStart.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
        jdbc.update("""
                INSERT INTO tasks (
                    id, ticket_number, category, system_name, estimated_minutes,
                    process_number, operation_date, operation_start_at, operation_end_at,
                    creator_id, current_assignee_id, status, auto_assignment_rule,
                    auto_assignment_explanation, actual_start_at, actual_end_at,
                    completed_at, completed_by_user_id, created_at, updated_at)
                VALUES (
                    UUID_TO_BIN(?), ?, 'VERSION_RELEASE', 'Reporting', ?, 'REP-1', ?, ?, ?,
                    UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'DAY_SECOND', 'seed', ?, ?,
                    ?, UUID_TO_BIN(?), ?, ?)
                """,
                id.toString(),
                "OPS-" + id.toString().replace("-", "").substring(0, 28),
                estimate,
                operationDate,
                utcTimestamp(operationStart),
                utcTimestamp(operationStart.plusSeconds(3600)),
                developer.id().toString(),
                assigneeId.toString(),
                status,
                actualStart == null ? null : utcTimestamp(actualStart),
                actualEnd == null ? null : utcTimestamp(actualEnd),
                "COMPLETED".equals(status) ? utcTimestamp(actualEnd) : null,
                "COMPLETED".equals(status) ? assigneeId.toString() : null,
                utcTimestamp(operationStart),
                utcTimestamp(operationStart));
    }

    private UserAccount account(String username, Set<RoleName> roles) {
        return users.saveAndFlush(UserAccount.create(
                username, username, passwords.encode("Reporting-Test-Password-1"),
                roles, false));
    }

    private CurrentUser current(UserAccount account) {
        return new CurrentUser(
                account.id(), account.username(), account.displayName(),
                account.roles(), false);
    }

    private Timestamp utcTimestamp(Instant instant) {
        return Timestamp.valueOf(
                java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC));
    }
}
