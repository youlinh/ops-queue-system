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
                    task_count, success_count, source_ip, occurred_at, created_at)
                VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?,
                    ?, 0, ?, ?, ?)
                """,
                commandId.toString(),
                actorId.toString(),
                sourceOperatorId.toString(),
                date,
                taskCount,
                normalizeSourceIp(sourceIp),
                timestamp,
                timestamp);
        return commandId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeCommand(UUID commandId) {
        PendingCommand command = jdbc.queryForObject("""
                SELECT BIN_TO_UUID(actor_id) actor_id,
                       BIN_TO_UUID(source_operator_id) source_operator_id,
                       operation_date, task_count, success_count,
                       source_ip, occurred_at
                FROM redistribution_audit_commands
                WHERE id = UUID_TO_BIN(?)
                FOR UPDATE
                """,
                (result, row) -> new PendingCommand(
                        UUID.fromString(result.getString("actor_id")),
                        UUID.fromString(result.getString("source_operator_id")),
                        result.getObject("operation_date", LocalDate.class),
                        result.getInt("task_count"),
                        result.getInt("success_count"),
                        result.getString("source_ip"),
                        result.getObject("occurred_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC)),
                commandId.toString());
        if (command == null) {
            return;
        }
        audits.record(
                command.actorId(),
                "REDISTRIBUTION_EXECUTED",
                "OPERATOR",
                command.sourceOperatorId(),
                Map.of(),
                Map.of(
                        "date", command.date().toString(),
                        "taskCount", command.taskCount(),
                        "successCount", command.successCount(),
                        "failureCount", command.taskCount() - command.successCount()),
                command.sourceIp(),
                command.occurredAt());
        jdbc.update(
                "DELETE FROM redistribution_audit_commands WHERE id = UUID_TO_BIN(?)",
                commandId.toString());
    }

    @Transactional(readOnly = true)
    public List<UUID> staleCommandIds(Instant cutoff) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(id)
                FROM redistribution_audit_commands
                WHERE created_at < ?
                ORDER BY created_at, id
                """,
                (result, row) -> UUID.fromString(result.getString(1)),
                timestamp(cutoff));
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
            int successCount,
            String sourceIp,
            Instant occurredAt) {
    }
}
