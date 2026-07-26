package com.acme.opsqueue.assignment;

import com.acme.opsqueue.audit.AuditService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RedistributionAuditTransaction {
    private final JdbcTemplate jdbc;
    private final AuditService audits;

    public RedistributionAuditTransaction(JdbcTemplate jdbc, AuditService audits) {
        this.jdbc = jdbc;
        this.audits = audits;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID begin(
            UUID actorId,
            UUID sourceOperatorId,
            LocalDate date,
            int taskCount,
            String sourceIp,
            Instant occurredAt) {
        UUID commandId = UUID.randomUUID();
        Timestamp timestamp = timestamp(occurredAt);
        jdbc.update("""
                INSERT INTO redistribution_audit_commands (
                    id, actor_id, source_operator_id, operation_date,
                    task_count, processed_count, success_count,
                    command_state, source_ip,
                    occurred_at, created_at, updated_at, lease_until)
                VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?,
                    ?, 0, 0, 'RUNNING', ?, ?,
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6),
                    DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 10 MINUTE))
                """,
                commandId.toString(),
                actorId.toString(),
                sourceOperatorId.toString(),
                date,
                taskCount,
                normalizeSourceIp(sourceIp),
                timestamp);
        return commandId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReady(UUID commandId) {
        int updated = jdbc.update("""
                UPDATE redistribution_audit_commands
                SET command_state = 'READY',
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = UUID_TO_BIN(?)
                  AND command_state = 'RUNNING'
                  AND processed_count = task_count
                """,
                commandId.toString());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Redistribution audit command cannot be completed");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finalizeCommand(UUID commandId) {
        List<PendingCommand> commands = jdbc.query("""
                SELECT BIN_TO_UUID(actor_id) actor_id,
                       BIN_TO_UUID(source_operator_id) source_operator_id,
                       operation_date, task_count, processed_count, success_count,
                       source_ip, occurred_at
                FROM redistribution_audit_commands
                WHERE id = UUID_TO_BIN(?)
                  AND command_state = 'READY'
                FOR UPDATE
                """,
                (result, row) -> new PendingCommand(
                        UUID.fromString(result.getString("actor_id")),
                        UUID.fromString(result.getString("source_operator_id")),
                        result.getObject("operation_date", LocalDate.class),
                        result.getInt("task_count"),
                        result.getInt("processed_count"),
                        result.getInt("success_count"),
                        result.getString("source_ip"),
                        result.getObject("occurred_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC)),
                commandId.toString());
        if (commands.isEmpty()) {
            return false;
        }
        PendingCommand command = commands.getFirst();
        writeAudit(
                command,
                "REDISTRIBUTION_EXECUTED",
                command.startedAt());
        jdbc.update(
                "DELETE FROM redistribution_audit_commands WHERE id = UUID_TO_BIN(?)",
                commandId.toString());
        return true;
    }

    @Transactional(readOnly = true)
    public List<UUID> readyCommandIds() {
        return jdbc.query("""
                SELECT BIN_TO_UUID(id)
                FROM redistribution_audit_commands
                WHERE command_state = 'READY'
                ORDER BY updated_at, id
                """,
                (result, row) -> UUID.fromString(result.getString(1)));
    }

    @Transactional(readOnly = true)
    public List<UUID> expiredRunningCommandIds() {
        return jdbc.query("""
                SELECT BIN_TO_UUID(id)
                FROM redistribution_audit_commands
                WHERE command_state = 'RUNNING'
                  AND lease_until < CURRENT_TIMESTAMP(6)
                ORDER BY lease_until, id
                """,
                (result, row) -> UUID.fromString(result.getString(1)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecoveryOutcome recoverExpiredCommand(UUID commandId) {
        List<PendingCommand> commands = jdbc.query("""
                SELECT BIN_TO_UUID(actor_id) actor_id,
                       BIN_TO_UUID(source_operator_id) source_operator_id,
                       operation_date, task_count, processed_count, success_count,
                       source_ip, occurred_at,
                       CURRENT_TIMESTAMP(6) reconciled_at
                FROM redistribution_audit_commands
                WHERE id = UUID_TO_BIN(?)
                  AND command_state = 'RUNNING'
                  AND lease_until < CURRENT_TIMESTAMP(6)
                FOR UPDATE
                """,
                (result, row) -> new PendingCommand(
                        UUID.fromString(result.getString("actor_id")),
                        UUID.fromString(result.getString("source_operator_id")),
                        result.getObject("operation_date", LocalDate.class),
                        result.getInt("task_count"),
                        result.getInt("processed_count"),
                        result.getInt("success_count"),
                        result.getString("source_ip"),
                        result.getObject("occurred_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC),
                        result.getObject("reconciled_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC)),
                commandId.toString());
        if (commands.isEmpty()) {
            return RecoveryOutcome.NOOP;
        }
        PendingCommand command = commands.getFirst();
        if (command.processedCount() == command.taskCount()) {
            jdbc.update("""
                    UPDATE redistribution_audit_commands
                    SET command_state = 'READY',
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = UUID_TO_BIN(?)
                      AND command_state = 'RUNNING'
                    """,
                    commandId.toString());
            return RecoveryOutcome.READY;
        }
        writeAudit(
                command,
                "REDISTRIBUTION_INTERRUPTED",
                command.reconciledAt());
        jdbc.update(
                "DELETE FROM redistribution_audit_commands WHERE id = UUID_TO_BIN(?)",
                commandId.toString());
        return RecoveryOutcome.INTERRUPTED;
    }

    private void writeAudit(
            PendingCommand command,
            String action,
            Instant actionAt) {
        audits.record(
                command.actorId(),
                action,
                "OPERATOR",
                command.sourceOperatorId(),
                Map.of(),
                Map.of(
                        "date", command.date().toString(),
                        "taskCount", command.taskCount(),
                        "processedCount", command.processedCount(),
                        "successCount", command.successCount(),
                        "failureCount", command.taskCount() - command.successCount(),
                        "startedAt", command.startedAt().toString()),
                command.sourceIp(),
                actionAt);
    }

    private String normalizeSourceIp(String sourceIp) {
        if (sourceIp == null || sourceIp.isBlank()) {
            return "unknown";
        }
        String normalized = sourceIp.trim();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private record PendingCommand(
            UUID actorId,
            UUID sourceOperatorId,
            LocalDate date,
            int taskCount,
            int processedCount,
            int successCount,
            String sourceIp,
            Instant startedAt,
            Instant reconciledAt) {
        private PendingCommand(
                UUID actorId,
                UUID sourceOperatorId,
                LocalDate date,
                int taskCount,
                int processedCount,
                int successCount,
                String sourceIp,
                Instant startedAt) {
            this(
                    actorId,
                    sourceOperatorId,
                    date,
                    taskCount,
                    processedCount,
                    successCount,
                    sourceIp,
                    startedAt,
                    startedAt);
        }
    }

    public enum RecoveryOutcome {
        NOOP,
        READY,
        INTERRUPTED
    }
}
