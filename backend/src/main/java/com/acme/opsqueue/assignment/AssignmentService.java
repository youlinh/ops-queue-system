package com.acme.opsqueue.assignment;

import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import com.acme.opsqueue.roster.DutyRoster;
import com.acme.opsqueue.roster.DutyRosterRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentService {
    static final String NEXT_DAY_DUTY_WARNING = "目标人员是次日值班人员";
    private static final int MAX_REASON_LENGTH = 1000;

    private final JdbcTemplate jdbc;
    private final UserAccountRepository users;
    private final DutyRosterRepository rosters;

    public AssignmentService(
            JdbcTemplate jdbc,
            UserAccountRepository users,
            DutyRosterRepository rosters) {
        this.jdbc = jdbc;
        this.users = users;
        this.rosters = rosters;
    }

    @Transactional
    public AssignmentResult transfer(
            UUID taskId,
            UUID actorId,
            UUID targetId,
            String reason,
            Instant at) {
        String normalizedReason = normalizeReason(reason);
        requireInstant(at);
        TaskAssignmentRecord task = lockTask(taskId);
        if (actorId == null || !task.currentAssigneeId().equals(actorId)) {
            throw AssignmentValidationException.forbidden(
                    "Only the current assignee can transfer this task");
        }
        requireMutable(task);
        UserAccount target = requireEligibleTarget(targetId, task, true);
        return updateAssignment(
                task, actorId, target.id(), normalizedReason, "TRANSFER",
                "MANUAL_TRANSFER", at);
    }

    @Transactional
    public AssignmentResult leaderAdjust(
            UUID taskId,
            UUID leaderId,
            UUID targetId,
            String reason,
            Instant at) {
        requireLeader(leaderId);
        String normalizedReason = normalizeReason(reason);
        requireInstant(at);
        TaskAssignmentRecord task = lockTask(taskId);
        requireMutable(task);
        UserAccount target = requireEligibleTarget(targetId, task, true);
        return updateAssignment(
                task, leaderId, target.id(), normalizedReason, "REASSIGN",
                "LEADER_ADJUSTMENT", at);
    }

    @Transactional
    public UnavailabilityView setUnavailable(
            UUID operatorId,
            LocalDate date,
            String reason,
            UUID leaderId,
            Instant at) {
        requireLeader(leaderId);
        UserAccount operator = requireEnabledOperator(operatorId);
        if (date == null) {
            throw AssignmentValidationException.invalidRequest(
                    "Unavailability date is required");
        }
        String normalizedReason = normalizeReason(reason);
        if (normalizedReason.length() > 255) {
            throw AssignmentValidationException.invalidRequest(
                    "Unavailability reason must not exceed 255 characters");
        }
        requireInstant(at);
        jdbc.update("""
                INSERT INTO unavailability (
                    user_id, unavailable_date, reason, created_by_user_id, created_at)
                VALUES (UUID_TO_BIN(?), ?, ?, UUID_TO_BIN(?), ?)
                ON DUPLICATE KEY UPDATE
                    reason = VALUES(reason),
                    created_by_user_id = VALUES(created_by_user_id),
                    created_at = VALUES(created_at)
                """,
                operator.id().toString(),
                date,
                normalizedReason,
                leaderId.toString(),
                timestamp(at));
        return new UnavailabilityView(
                operator.id(), date, normalizedReason, leaderId, at);
    }

    @Transactional
    public void removeUnavailable(UUID operatorId, LocalDate date, UUID leaderId) {
        requireLeader(leaderId);
        if (date == null) {
            throw AssignmentValidationException.invalidRequest(
                    "Unavailability date is required");
        }
        UserAccount operator = requireEnabledOperator(operatorId);
        jdbc.update("""
                DELETE FROM unavailability
                WHERE user_id = UUID_TO_BIN(?) AND unavailable_date = ?
                """, operator.id().toString(), date);
    }

    @Transactional(readOnly = true)
    public List<OperatorOption> operatorDirectory(LocalDate operationDate) {
        if (operationDate == null) {
            throw AssignmentValidationException.invalidRequest(
                    "Operation date is required");
        }
        return jdbc.query("""
                SELECT BIN_TO_UUID(u.id) id, u.display_name,
                       NOT EXISTS (
                           SELECT 1
                           FROM unavailability unavailable
                           WHERE unavailable.user_id = u.id
                             AND unavailable.unavailable_date = ?
                       ) available
                FROM users u
                JOIN user_roles role ON role.user_id = u.id
                WHERE u.enabled = TRUE
                  AND role.role_name = 'OPERATOR'
                ORDER BY u.display_name, u.id
                """,
                (result, row) -> new OperatorOption(
                        UUID.fromString(result.getString("id")),
                        result.getString("display_name"),
                        result.getBoolean("available")),
                operationDate);
    }

    void requireLeader(UUID actorId) {
        if (actorId == null) {
            throw AssignmentValidationException.forbidden(
                    "An enabled leader is required");
        }
        UserAccount actor = users.findById(actorId)
                .orElseThrow(() -> AssignmentValidationException.forbidden(
                        "An enabled leader is required"));
        if (!actor.enabled() || !actor.hasRole(RoleName.LEADER)) {
            throw AssignmentValidationException.forbidden(
                    "An enabled leader is required");
        }
    }

    void requireOperator(UUID operatorId) {
        if (operatorId == null) {
            throw AssignmentValidationException.invalidRequest(
                    "Operator is required");
        }
        UserAccount operator = users.findById(operatorId)
                .orElseThrow(() -> AssignmentValidationException.invalidTarget(
                        "Operator does not exist"));
        if (!operator.hasRole(RoleName.OPERATOR)) {
            throw AssignmentValidationException.invalidTarget(
                    "Selected user is not an operator");
        }
    }

    private AssignmentResult updateAssignment(
            TaskAssignmentRecord task,
            UUID actorId,
            UUID targetId,
            String reason,
            String historyType,
            String assignmentRule,
            Instant at) {
        int updated = jdbc.update("""
                UPDATE tasks
                SET current_assignee_id = UUID_TO_BIN(?),
                    needs_manual_attention = FALSE,
                    version = version + 1,
                    updated_at = ?
                WHERE id = UUID_TO_BIN(?) AND version = ?
                """,
                targetId.toString(),
                timestamp(at),
                task.id().toString(),
                task.version());
        if (updated != 1) {
            throw AssignmentValidationException.conflict(
                    "Task changed before the assignment could be updated");
        }
        insertHistory(
                task.id(),
                historyType,
                task.currentAssigneeId(),
                targetId,
                assignmentRule,
                reason,
                actorId,
                at);
        List<String> warnings = nextDayDutyWarning(
                task.operationDate(), targetId);
        return new AssignmentResult(
                task.id(),
                task.currentAssigneeId(),
                targetId,
                warnings,
                task.version() + 1);
    }

    public record OperatorOption(
            UUID id,
            String displayName,
            boolean available) {
    }

    private void insertHistory(
            UUID taskId,
            String type,
            UUID oldAssignee,
            UUID newAssignee,
            String rule,
            String reason,
            UUID actorId,
            Instant at) {
        jdbc.update("""
                INSERT INTO assignment_histories (
                    id, task_id, assignment_type, old_assignee_id, new_assignee_id,
                    assignment_rule, reason, candidate_snapshot, actor_id, assigned_at)
                VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), ?, UUID_TO_BIN(?), UUID_TO_BIN(?),
                    ?, ?, CAST('[]' AS JSON), UUID_TO_BIN(?), ?)
                """,
                UUID.randomUUID().toString(),
                taskId.toString(),
                type,
                oldAssignee.toString(),
                newAssignee.toString(),
                rule,
                reason,
                actorId.toString(),
                timestamp(at));
    }

    private UserAccount requireEligibleTarget(
            UUID targetId,
            TaskAssignmentRecord task,
            boolean rejectCurrent) {
        UserAccount target = requireEnabledOperator(targetId);
        if (rejectCurrent && target.id().equals(task.currentAssigneeId())) {
            throw AssignmentValidationException.invalidTarget(
                    "Target must be different from the current assignee");
        }
        Integer unavailable = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM unavailability
                WHERE user_id = UUID_TO_BIN(?) AND unavailable_date = ?
                """,
                Integer.class,
                target.id().toString(),
                task.operationDate());
        if (unavailable != null && unavailable > 0) {
            throw AssignmentValidationException.invalidTarget(
                    "Target operator is unavailable on the operation date");
        }
        return target;
    }

    private UserAccount requireEnabledOperator(UUID targetId) {
        if (targetId == null) {
            throw AssignmentValidationException.invalidTarget(
                    "Target operator is required");
        }
        UserAccount target = users.findById(targetId)
                .orElseThrow(() -> AssignmentValidationException.invalidTarget(
                        "Target operator does not exist"));
        if (!target.enabled()) {
            throw AssignmentValidationException.invalidTarget(
                    "Target operator is disabled");
        }
        if (!target.hasRole(RoleName.OPERATOR)) {
            throw AssignmentValidationException.invalidTarget(
                    "Target user is not an operator");
        }
        return target;
    }

    private TaskAssignmentRecord lockTask(UUID taskId) {
        if (taskId == null) {
            throw AssignmentValidationException.notFound("Task was not found");
        }
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

    private void requireMutable(TaskAssignmentRecord task) {
        if (!"PENDING".equals(task.status()) && !"IN_PROGRESS".equals(task.status())) {
            throw AssignmentValidationException.conflict(
                    "Only pending or in-progress tasks can be reassigned");
        }
    }

    private List<String> nextDayDutyWarning(
            LocalDate operationDate, UUID targetId) {
        return rosters.findByDutyDate(operationDate.plusDays(1))
                .filter(roster -> isDutyUser(roster, targetId))
                .map(roster -> List.of(NEXT_DAY_DUTY_WARNING))
                .orElseGet(List::of);
    }

    private boolean isDutyUser(DutyRoster roster, UUID targetId) {
        return roster.secondLineId().equals(targetId)
                || roster.thirdLineId().equals(targetId);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw AssignmentValidationException.invalidRequest(
                    "A nonblank reason is required");
        }
        String normalized = reason.trim();
        if (normalized.length() > MAX_REASON_LENGTH) {
            throw AssignmentValidationException.invalidRequest(
                    "Reason must not exceed 1000 characters");
        }
        return normalized;
    }

    private void requireInstant(Instant at) {
        if (at == null) {
            throw AssignmentValidationException.invalidRequest(
                    "Operation timestamp is required");
        }
    }

    static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
