package com.acme.opsqueue.task;

import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import com.acme.opsqueue.roster.DutyRoster;
import com.acme.opsqueue.roster.DutyRosterRepository;
import com.acme.opsqueue.scheduling.AssignmentDecision;
import com.acme.opsqueue.scheduling.AssignmentInput;
import com.acme.opsqueue.scheduling.AssignmentRule;
import com.acme.opsqueue.scheduling.AutoAssignmentEngine;
import com.acme.opsqueue.scheduling.CandidateMetric;
import com.acme.opsqueue.scheduling.DutyPair;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskCreationTransaction {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final DutyRosterRepository rosters;
    private final UserAccountRepository users;
    private final AutoAssignmentEngine assignmentEngine;
    private final ObjectMapper objectMapper;

    public TaskCreationTransaction(
            JdbcTemplate jdbc,
            DutyRosterRepository rosters,
            UserAccountRepository users,
            AutoAssignmentEngine assignmentEngine,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.rosters = rosters;
        this.users = users;
        this.assignmentEngine = assignmentEngine;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatedTask execute(
            CreateTaskCommand command,
            UUID creatorId,
            Instant submittedAt) {
        ZonedDateTime operationStart = command.operationStart().atZone(BUSINESS_ZONE);
        LocalDate operationDate = operationStart.toLocalDate();
        lockScheduleDate(operationDate);

        DutyRoster duty = rosters.findByDutyDate(operationDate)
                .orElseThrow(() -> new MissingDutyRosterException(operationDate));
        List<UserAccount> activeOperators = users.findEnabledByRole(RoleName.OPERATOR);
        Set<UUID> activeOperatorIds = activeOperators.stream()
                .map(UserAccount::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        MetricState metricState = loadMetricState(operationDate);
        List<CandidateMetric> metrics = buildMetrics(
                activeOperators, metricState, Set.of());
        AssignmentInput input = assignmentInput(
                submittedAt, operationStart, duty, activeOperatorIds, metrics);
        AssignmentDecision decision = assignmentEngine.assign(input);

        if (decision.rule() == AssignmentRule.FAIR) {
            LocalDate nextDate = operationDate.plusDays(1);
            DutyRoster nextDuty = rosters.findByDutyDate(nextDate)
                    .orElseThrow(() -> new MissingNextDayDutyRosterException(nextDate));
            Set<UUID> nextDutyIds =
                    Set.of(nextDuty.secondLineId(), nextDuty.thirdLineId());
            metrics = buildMetrics(activeOperators, metricState, nextDutyIds);
            decision = assignmentEngine.assign(assignmentInput(
                    submittedAt, operationStart, duty, activeOperatorIds, metrics));
        }

        LocalDate issueDate = submittedAt.atZone(BUSINESS_ZONE).toLocalDate();
        int sequence = nextTicketSequence(issueDate);
        String ticketNumber = "OPS-%s-%04d".formatted(
                issueDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                sequence);
        UUID taskId = UUID.randomUUID();
        insertTask(
                taskId,
                ticketNumber,
                command,
                operationDate,
                creatorId,
                decision,
                submittedAt);
        insertHistory(taskId, creatorId, decision, submittedAt);
        return new CreatedTask(
                taskId, ticketNumber, decision.assigneeId(), decision.rule());
    }

    private void lockScheduleDate(LocalDate operationDate) {
        jdbc.update("""
                INSERT INTO schedule_date_locks (business_date)
                VALUES (?)
                ON DUPLICATE KEY UPDATE business_date = VALUES(business_date)
                """, operationDate);
        jdbc.queryForObject("""
                SELECT business_date
                FROM schedule_date_locks
                WHERE business_date = ?
                FOR UPDATE
                """, LocalDate.class, operationDate);
    }

    private MetricState loadMetricState(LocalDate operationDate) {
        Map<UUID, Integer> dailyCounts = new HashMap<>();
        jdbc.query("""
                SELECT BIN_TO_UUID(current_assignee_id), COUNT(*)
                FROM tasks
                WHERE operation_date = ?
                GROUP BY current_assignee_id
                """, result -> {
                    dailyCounts.put(
                            UUID.fromString(result.getString(1)), result.getInt(2));
                },
                operationDate);

        LocalDate monthStart = operationDate.withDayOfMonth(1);
        LocalDate nextMonth = monthStart.plusMonths(1);
        Map<UUID, Long> monthlyMinutes = new HashMap<>();
        jdbc.query("""
                SELECT BIN_TO_UUID(current_assignee_id),
                       COALESCE(SUM(TIMESTAMPDIFF(
                           MINUTE, actual_start_at, actual_end_at)), 0)
                FROM tasks
                WHERE operation_date >= ?
                  AND operation_date < ?
                  AND status = 'COMPLETED'
                  AND actual_start_at IS NOT NULL
                  AND actual_end_at IS NOT NULL
                GROUP BY current_assignee_id
                """, result -> {
                    monthlyMinutes.put(
                            UUID.fromString(result.getString(1)), result.getLong(2));
                },
                monthStart,
                nextMonth);

        Map<UUID, Instant> latestAssignments = new HashMap<>();
        jdbc.query("""
                SELECT BIN_TO_UUID(new_assignee_id), MAX(assigned_at)
                FROM assignment_histories
                GROUP BY new_assignee_id
                """, result -> {
                    latestAssignments.put(
                            UUID.fromString(result.getString(1)),
                            result.getObject(2, LocalDateTime.class)
                                    .toInstant(ZoneOffset.UTC));
                });

        Set<UUID> unavailable = new HashSet<>();
        jdbc.query("""
                SELECT BIN_TO_UUID(user_id)
                FROM unavailability
                WHERE unavailable_date = ?
                """, result -> {
                    unavailable.add(UUID.fromString(result.getString(1)));
                },
                operationDate);
        return new MetricState(
                Map.copyOf(dailyCounts),
                Map.copyOf(monthlyMinutes),
                Map.copyOf(latestAssignments),
                Set.copyOf(unavailable));
    }

    private List<CandidateMetric> buildMetrics(
            List<UserAccount> activeOperators,
            MetricState state,
            Set<UUID> nextDutyIds) {
        return activeOperators.stream()
                .map(operator -> new CandidateMetric(
                        operator.id(),
                        state.dailyCounts().getOrDefault(operator.id(), 0),
                        state.monthlyMinutes().getOrDefault(operator.id(), 0L),
                        state.latestAssignments().get(operator.id()),
                        !state.unavailable().contains(operator.id()),
                        nextDutyIds.contains(operator.id())))
                .toList();
    }

    private AssignmentInput assignmentInput(
            Instant submittedAt,
            ZonedDateTime operationStart,
            DutyRoster duty,
            Set<UUID> activeOperatorIds,
            List<CandidateMetric> metrics) {
        return new AssignmentInput(
                submittedAt,
                operationStart,
                new DutyPair(duty.secondLineId(), duty.thirdLineId()),
                activeOperatorIds,
                metrics);
    }

    private int nextTicketSequence(LocalDate issueDate) {
        jdbc.update("""
                INSERT INTO daily_ticket_sequences (issue_date, last_sequence)
                VALUES (?, 0)
                ON DUPLICATE KEY UPDATE issue_date = VALUES(issue_date)
                """, issueDate);
        Integer current = jdbc.queryForObject("""
                SELECT last_sequence
                FROM daily_ticket_sequences
                WHERE issue_date = ?
                FOR UPDATE
                """, Integer.class, issueDate);
        int next = Math.addExact(current, 1);
        jdbc.update("""
                UPDATE daily_ticket_sequences
                SET last_sequence = ?
                WHERE issue_date = ?
                """, next, issueDate);
        return next;
    }

    private void insertTask(
            UUID taskId,
            String ticketNumber,
            CreateTaskCommand command,
            LocalDate operationDate,
            UUID creatorId,
            AssignmentDecision decision,
            Instant submittedAt) {
        Timestamp submitted = utcTimestamp(submittedAt);
        jdbc.update("""
                INSERT INTO tasks (
                    id, ticket_number, category, system_name, estimated_minutes,
                    process_number, operation_date, operation_start_at, operation_end_at,
                    creator_id, current_assignee_id, status, auto_assignment_rule,
                    auto_assignment_explanation, version, created_at, updated_at)
                VALUES (
                    UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?, ?, ?,
                    UUID_TO_BIN(?), UUID_TO_BIN(?), 'PENDING', ?, ?, 0, ?, ?)
                """,
                taskId.toString(),
                ticketNumber,
                command.category().name(),
                command.systemName(),
                command.estimatedMinutes(),
                command.processNumber(),
                operationDate,
                utcTimestamp(command.operationStart()),
                utcTimestamp(command.operationEnd()),
                creatorId.toString(),
                decision.assigneeId().toString(),
                decision.rule().name(),
                decision.explanation(),
                submitted,
                submitted);
    }

    private void insertHistory(
            UUID taskId,
            UUID creatorId,
            AssignmentDecision decision,
            Instant submittedAt) {
        jdbc.update("""
                INSERT INTO assignment_histories (
                    id, task_id, assignment_type, old_assignee_id, new_assignee_id,
                    assignment_rule, reason, candidate_snapshot, actor_id, assigned_at)
                VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), 'AUTO', NULL, UUID_TO_BIN(?),
                    ?, ?, CAST(? AS JSON), UUID_TO_BIN(?), ?)
                """,
                UUID.randomUUID().toString(),
                taskId.toString(),
                decision.assigneeId().toString(),
                decision.rule().name(),
                decision.explanation(),
                serializeCandidates(decision),
                creatorId.toString(),
                utcTimestamp(submittedAt));
    }

    private String serializeCandidates(AssignmentDecision decision) {
        try {
            return objectMapper.writeValueAsString(decision.candidates());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize assignment evidence", exception);
        }
    }

    private Timestamp utcTimestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private record MetricState(
            Map<UUID, Integer> dailyCounts,
            Map<UUID, Long> monthlyMinutes,
            Map<UUID, Instant> latestAssignments,
            Set<UUID> unavailable) {
    }
}
