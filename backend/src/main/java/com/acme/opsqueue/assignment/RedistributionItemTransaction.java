package com.acme.opsqueue.assignment;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
class RedistributionItemTransaction {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final DutyRosterRepository rosters;
    private final UserAccountRepository users;
    private final AutoAssignmentEngine assignmentEngine;
    private final ObjectMapper objectMapper;
    private final AssignmentService assignments;

    RedistributionItemTransaction(
            JdbcTemplate jdbc,
            DutyRosterRepository rosters,
            UserAccountRepository users,
            AutoAssignmentEngine assignmentEngine,
            ObjectMapper objectMapper,
            AssignmentService assignments) {
        this.jdbc = jdbc;
        this.rosters = rosters;
        this.users = users;
        this.assignmentEngine = assignmentEngine;
        this.objectMapper = objectMapper;
        this.assignments = assignments;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RedistributionItemResult execute(
            UUID taskId,
            UUID sourceOperatorId,
            LocalDate operationDate,
            UUID leaderId,
            String reason,
            Instant at,
            UUID auditCommandId) {
        assignments.requireLeader(leaderId);
        renewAuditLease(auditCommandId);
        lockScheduleDate(operationDate);
        TaskAssignmentRecord task = lockTask(taskId);
        if (!"PENDING".equals(task.status())
                || !task.operationDate().equals(operationDate)
                || !task.currentAssigneeId().equals(sourceOperatorId)) {
            return RedistributionItemResult.failure(
                    task, "Task status or assignment changed", false);
        }

        AssignmentDecision decision;
        try {
            decision = decide(task, at);
        } catch (RuntimeException exception) {
            setManualAttention(task.id(), at);
            return RedistributionItemResult.failure(
                    task, assignmentError(exception), true);
        }
        if (decision.assigneeId().equals(task.currentAssigneeId())) {
            setManualAttention(task.id(), at);
            return RedistributionItemResult.failure(
                    task, "Automatic assignment did not find a different operator", true);
        }

        String candidates = serializeCandidates(decision);
        int updated = jdbc.update("""
                UPDATE tasks
                SET current_assignee_id = UUID_TO_BIN(?),
                    auto_assignment_rule = ?,
                    auto_assignment_explanation = ?,
                    needs_manual_attention = FALSE,
                    version = version + 1,
                    updated_at = ?
                WHERE id = UUID_TO_BIN(?)
                  AND current_assignee_id = UUID_TO_BIN(?)
                  AND status = 'PENDING'
                  AND version = ?
                """,
                decision.assigneeId().toString(),
                decision.rule().name(),
                decision.explanation(),
                AssignmentService.timestamp(at),
                task.id().toString(),
                task.currentAssigneeId().toString(),
                task.version());
        if (updated != 1) {
            throw AssignmentValidationException.conflict(
                    "Task changed during redistribution");
        }
        jdbc.update("""
                INSERT INTO assignment_histories (
                    id, task_id, assignment_type, old_assignee_id, new_assignee_id,
                    assignment_rule, reason, candidate_snapshot, actor_id, assigned_at)
                VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), 'REASSIGN',
                    UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, CAST(? AS JSON),
                    UUID_TO_BIN(?), ?)
                """,
                UUID.randomUUID().toString(),
                task.id().toString(),
                task.currentAssigneeId().toString(),
                decision.assigneeId().toString(),
                decision.rule().name(),
                reason,
                candidates,
                leaderId.toString(),
                AssignmentService.timestamp(at));
        int auditUpdated = jdbc.update("""
                UPDATE redistribution_audit_commands
                SET success_count = success_count + 1,
                    updated_at = CURRENT_TIMESTAMP(6),
                    lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 10 MINUTE)
                WHERE id = UUID_TO_BIN(?)
                  AND command_state = 'RUNNING'
                  AND success_count < task_count
                """,
                auditCommandId.toString());
        if (auditUpdated != 1) {
            throw new IllegalStateException(
                    "Redistribution audit command is unavailable");
        }
        return RedistributionItemResult.success(task, decision.assigneeId());
    }

    private void renewAuditLease(UUID auditCommandId) {
        int updated = jdbc.update("""
                UPDATE redistribution_audit_commands
                SET updated_at = CURRENT_TIMESTAMP(6),
                    lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 10 MINUTE)
                WHERE id = UUID_TO_BIN(?)
                  AND command_state = 'RUNNING'
                """,
                auditCommandId.toString());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Redistribution audit command is unavailable");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markManualAttention(
            UUID taskId,
            UUID sourceOperatorId,
            LocalDate operationDate,
            Instant at) {
        int updated = jdbc.update("""
                UPDATE tasks
                SET needs_manual_attention = TRUE,
                    updated_at = ?,
                    version = version + 1
                WHERE id = UUID_TO_BIN(?)
                  AND current_assignee_id = UUID_TO_BIN(?)
                  AND operation_date = ?
                  AND status = 'PENDING'
                """,
                AssignmentService.timestamp(at),
                taskId.toString(),
                sourceOperatorId.toString(),
                operationDate);
        return updated == 1;
    }

    private AssignmentDecision decide(
            TaskAssignmentRecord task,
            Instant at) {
        DutyRoster duty = rosters.findByDutyDate(task.operationDate())
                .orElseThrow(() -> new IllegalStateException(
                        "Duty roster is missing for " + task.operationDate()));
        List<UserAccount> activeOperators =
                users.findEnabledByRole(RoleName.OPERATOR);
        Set<UUID> activeIds = activeOperators.stream()
                .map(UserAccount::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!activeIds.contains(duty.secondLineId())
                || !activeIds.contains(duty.thirdLineId())) {
            throw new IllegalStateException(
                    "Duty roster contains an inactive operator");
        }
        MetricState state = loadMetricState(task.operationDate());
        List<CandidateMetric> metrics =
                buildMetrics(activeOperators, state, Set.of());
        AssignmentDecision decision = assignmentEngine.assign(input(
                task, at, duty, activeIds, metrics));
        if (decision.rule() == AssignmentRule.FAIR) {
            DutyRoster nextDuty = rosters.findByDutyDate(
                            task.operationDate().plusDays(1))
                    .orElseThrow(() -> new IllegalStateException(
                            "Next-day duty roster is missing"));
            Set<UUID> nextDutyIds =
                    Set.of(nextDuty.secondLineId(), nextDuty.thirdLineId());
            metrics = buildMetrics(activeOperators, state, nextDutyIds);
            decision = assignmentEngine.assign(input(
                    task, at, duty, activeIds, metrics));
        }
        return decision;
    }

    private AssignmentInput input(
            TaskAssignmentRecord task,
            Instant at,
            DutyRoster duty,
            Set<UUID> activeIds,
            List<CandidateMetric> metrics) {
        return new AssignmentInput(
                at,
                task.operationStart().atZone(BUSINESS_ZONE),
                new DutyPair(duty.secondLineId(), duty.thirdLineId()),
                activeIds,
                metrics);
    }

    private MetricState loadMetricState(LocalDate operationDate) {
        Map<UUID, Integer> dailyCounts = new HashMap<>();
        jdbc.query("""
                SELECT BIN_TO_UUID(current_assignee_id), COUNT(*)
                FROM tasks
                WHERE operation_date = ?
                GROUP BY current_assignee_id
                """,
                result -> {
                    dailyCounts.put(
                            UUID.fromString(result.getString(1)),
                            result.getInt(2));
                },
                operationDate);

        LocalDate monthStart = operationDate.withDayOfMonth(1);
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
                """,
                result -> {
                    monthlyMinutes.put(
                            UUID.fromString(result.getString(1)),
                            result.getLong(2));
                },
                monthStart,
                monthStart.plusMonths(1));

        Map<UUID, Instant> latestAssignments = new HashMap<>();
        jdbc.query("""
                SELECT BIN_TO_UUID(new_assignee_id), MAX(assigned_at)
                FROM assignment_histories
                GROUP BY new_assignee_id
                """,
                result -> {
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
                """,
                result -> {
                    unavailable.add(
                            UUID.fromString(result.getString(1)));
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

    private TaskAssignmentRecord lockTask(UUID taskId) {
        List<TaskAssignmentRecord> rows = jdbc.query("""
                SELECT BIN_TO_UUID(id) id, ticket_number, operation_date,
                       operation_start_at, BIN_TO_UUID(current_assignee_id) assignee_id,
                       status, version
                FROM tasks
                WHERE id = UUID_TO_BIN(?)
                FOR UPDATE
                """,
                (result, row) -> new TaskAssignmentRecord(
                        UUID.fromString(result.getString("id")),
                        result.getString("ticket_number"),
                        result.getObject("operation_date", LocalDate.class),
                        result.getObject("operation_start_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC),
                        UUID.fromString(result.getString("assignee_id")),
                        result.getString("status"),
                        result.getLong("version")),
                taskId.toString());
        if (rows.isEmpty()) {
            throw AssignmentValidationException.notFound("Task was not found");
        }
        return rows.getFirst();
    }

    private void setManualAttention(UUID taskId, Instant at) {
        jdbc.update("""
                UPDATE tasks
                SET needs_manual_attention = TRUE,
                    updated_at = ?,
                    version = version + 1
                WHERE id = UUID_TO_BIN(?)
                """, AssignmentService.timestamp(at), taskId.toString());
    }

    private String serializeCandidates(AssignmentDecision decision) {
        try {
            return objectMapper.writeValueAsString(decision.candidates());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Assignment evidence could not be serialized", exception);
        }
    }

    private String assignmentError(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "No eligible operator is available"
                : message;
    }

    private record MetricState(
            Map<UUID, Integer> dailyCounts,
            Map<UUID, Long> monthlyMinutes,
            Map<UUID, Instant> latestAssignments,
            Set<UUID> unavailable) {
    }
}
