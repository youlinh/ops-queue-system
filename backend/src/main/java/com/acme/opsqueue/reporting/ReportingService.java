package com.acme.opsqueue.reporting;

import com.acme.opsqueue.identity.CurrentUser;
import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportingService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final UserAccountRepository users;

    public ReportingService(JdbcTemplate jdbc, UserAccountRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public DailyOperatorReport daily(
            LocalDate date, UUID operatorId, CurrentUser requester) {
        if (date == null) {
            throw new IllegalArgumentException("Date is required");
        }
        requireReportAccess(operatorId, requester);
        Metrics metrics = jdbc.queryForObject("""
                SELECT COUNT(*) total_count,
                       COALESCE(SUM(status = 'PENDING'), 0) pending_count,
                       COALESCE(SUM(status = 'IN_PROGRESS'), 0) in_progress_count,
                       COALESCE(SUM(status = 'COMPLETED'), 0) completed_count,
                       COALESCE(SUM(estimated_minutes), 0) estimated_minutes,
                       COALESCE(SUM(CASE
                           WHEN status = 'COMPLETED'
                            AND actual_start_at IS NOT NULL
                            AND actual_end_at IS NOT NULL
                           THEN TIMESTAMPDIFF(MINUTE, actual_start_at, actual_end_at)
                           ELSE 0 END), 0) actual_minutes
                FROM tasks
                WHERE current_assignee_id = UUID_TO_BIN(?)
                  AND operation_date = ?
                """,
                (result, row) -> metrics(result),
                operatorId.toString(),
                date);
        return new DailyOperatorReport(
                operatorId, date, metrics.total(), metrics.pending(),
                metrics.inProgress(), metrics.completed(),
                metrics.estimated(), metrics.actual());
    }

    @Transactional(readOnly = true)
    public MonthlyOperatorReport monthly(
            YearMonth month, UUID operatorId, CurrentUser requester) {
        if (month == null) {
            throw new IllegalArgumentException("Month is required");
        }
        requireReportAccess(operatorId, requester);
        Instant start = month.atDay(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Metrics metrics = jdbc.queryForObject("""
                SELECT COUNT(*) total_count,
                       COALESCE(SUM(status = 'PENDING'), 0) pending_count,
                       COALESCE(SUM(status = 'IN_PROGRESS'), 0) in_progress_count,
                       COALESCE(SUM(status = 'COMPLETED'), 0) completed_count,
                       COALESCE(SUM(estimated_minutes), 0) estimated_minutes,
                       COALESCE(SUM(CASE
                           WHEN status = 'COMPLETED'
                            AND actual_start_at IS NOT NULL
                            AND actual_end_at IS NOT NULL
                           THEN TIMESTAMPDIFF(MINUTE, actual_start_at, actual_end_at)
                           ELSE 0 END), 0) actual_minutes
                FROM tasks
                WHERE current_assignee_id = UUID_TO_BIN(?)
                  AND operation_start_at >= ?
                  AND operation_start_at < ?
                """,
                (result, row) -> metrics(result),
                operatorId.toString(),
                timestamp(start),
                timestamp(end));
        return new MonthlyOperatorReport(
                operatorId, month, metrics.total(), metrics.pending(),
                metrics.inProgress(), metrics.completed(),
                metrics.estimated(), metrics.actual());
    }

    private void requireReportAccess(UUID operatorId, CurrentUser requester) {
        if (operatorId == null || requester == null) {
            throw new AccessDeniedException("An authenticated operator is required");
        }
        boolean leader = requester.roles().contains(RoleName.LEADER);
        boolean operator = requester.roles().contains(RoleName.OPERATOR);
        if (!leader && (!operator || !requester.id().equals(operatorId))) {
            throw new AccessDeniedException("Operators may only view their own workload");
        }
        UserAccount target = users.findById(operatorId)
                .orElseThrow(() -> new IllegalArgumentException("Operator does not exist"));
        if (!target.enabled() || !target.hasRole(RoleName.OPERATOR)) {
            throw new IllegalArgumentException("Target must be an enabled operator");
        }
    }

    private Metrics metrics(java.sql.ResultSet result) throws java.sql.SQLException {
        return new Metrics(
                result.getLong("total_count"),
                result.getLong("pending_count"),
                result.getLong("in_progress_count"),
                result.getLong("completed_count"),
                result.getLong("estimated_minutes"),
                result.getLong("actual_minutes"));
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private record Metrics(
            long total,
            long pending,
            long inProgress,
            long completed,
            long estimated,
            long actual) {
    }
}
