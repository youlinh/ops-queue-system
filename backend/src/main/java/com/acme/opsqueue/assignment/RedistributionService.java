package com.acme.opsqueue.assignment;

import com.acme.opsqueue.audit.AuditService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedistributionService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedistributionService.class);
    private final JdbcTemplate jdbc;
    private final AssignmentService assignments;
    private final RedistributionItemTransaction itemTransaction;
    private final RedistributionAuditTransaction auditTransactions;
    private final AuditService audits;

    public RedistributionService(
            JdbcTemplate jdbc,
            AssignmentService assignments,
            RedistributionItemTransaction itemTransaction,
            RedistributionAuditTransaction auditTransactions,
            AuditService audits) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.itemTransaction = itemTransaction;
        this.auditTransactions = auditTransactions;
        this.audits = audits;
    }

    public List<RedistributionTask> previewRedistribution(
            UUID operatorId,
            LocalDate date,
            UUID leaderId) {
        assignments.requireLeader(leaderId);
        validateSelection(operatorId, date);
        return jdbc.query("""
                SELECT BIN_TO_UUID(id) id, ticket_number, category, system_name,
                       operation_start_at, BIN_TO_UUID(current_assignee_id) assignee_id
                FROM tasks
                WHERE current_assignee_id = UUID_TO_BIN(?)
                  AND operation_date = ?
                  AND status = 'PENDING'
                ORDER BY ticket_number
                """,
                (result, row) -> new RedistributionTask(
                        UUID.fromString(result.getString("id")),
                        result.getString("ticket_number"),
                        result.getString("category"),
                        result.getString("system_name"),
                        result.getObject("operation_start_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC),
                        UUID.fromString(result.getString("assignee_id"))),
                operatorId.toString(),
                date);
    }

    public RedistributionResult redistribute(
            UUID operatorId,
            LocalDate date,
            UUID leaderId,
            String reason,
            Instant at) {
        assignments.requireLeader(leaderId);
        validateSelection(operatorId, date);
        String normalizedReason = normalizeReason(reason);
        if (at == null) {
            throw AssignmentValidationException.invalidRequest(
                    "Operation timestamp is required");
        }
        List<RedistributionTask> pending =
                previewRedistribution(operatorId, date, leaderId);
        UUID auditCommandId = auditTransactions.begin(
                leaderId,
                operatorId,
                date,
                pending.size(),
                audits.currentSourceIp(),
                at);
        List<RedistributionItemResult> results = new ArrayList<>(pending.size());
        for (RedistributionTask task : pending) {
            try {
                results.add(itemTransaction.execute(
                        task.taskId(), operatorId, date, leaderId,
                        normalizedReason, at, auditCommandId));
            } catch (RuntimeException exception) {
                boolean marked = itemTransaction.markManualAttention(
                        task.taskId(), operatorId, date, at);
                results.add(new RedistributionItemResult(
                        task.taskId(),
                        task.ticketNumber(),
                        false,
                        operatorId,
                        operatorId,
                        marked,
                        "Task redistribution failed"));
            }
        }
        try {
            auditTransactions.finalizeCommand(auditCommandId);
        } catch (DataAccessException exception) {
            LOGGER.error(
                    "Redistribution {} completed with a pending audit command",
                    auditCommandId,
                    exception);
        }
        return new RedistributionResult(operatorId, date, results);
    }

    private void validateSelection(UUID operatorId, LocalDate date) {
        assignments.requireOperator(operatorId);
        if (date == null) {
            throw AssignmentValidationException.invalidRequest(
                    "Operation date is required");
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw AssignmentValidationException.invalidRequest(
                    "A nonblank reason is required");
        }
        String normalized = reason.trim();
        if (normalized.length() > 1000) {
            throw AssignmentValidationException.invalidRequest(
                    "Reason must not exceed 1000 characters");
        }
        return normalized;
    }
}
