package com.acme.opsqueue.assignment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedistributionService {
    private final JdbcTemplate jdbc;
    private final AssignmentService assignments;
    private final RedistributionItemTransaction itemTransaction;

    public RedistributionService(
            JdbcTemplate jdbc,
            AssignmentService assignments,
            RedistributionItemTransaction itemTransaction) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.itemTransaction = itemTransaction;
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
        List<RedistributionItemResult> results = new ArrayList<>(pending.size());
        for (RedistributionTask task : pending) {
            try {
                results.add(itemTransaction.execute(
                        task.taskId(), operatorId, date, leaderId,
                        normalizedReason, at));
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
