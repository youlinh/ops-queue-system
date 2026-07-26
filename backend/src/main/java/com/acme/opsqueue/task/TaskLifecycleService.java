package com.acme.opsqueue.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskLifecycleService {
    /** One full day; longer completions indicate a typo rather than real work. */
    static final int MAX_ACTUAL_MINUTES = 1440;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TaskLifecycleService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TaskView call(UUID taskId, UUID actorId, Instant calledAt) {
        TaskRecord task = requireTask(taskId);
        requireAssignee(task, actorId);
        if (!"PENDING".equals(task.status())) {
            throw TaskLifecycleException.conflict("Only pending tasks can be called");
        }
        int updated = jdbc.update("""
                UPDATE tasks
                SET status = 'IN_PROGRESS', called_at = ?, called_by_user_id = UUID_TO_BIN(?),
                    updated_at = ?, version = version + 1
                WHERE id = UUID_TO_BIN(?) AND version = ? AND status = 'PENDING'
                """, timestamp(calledAt), actorId.toString(), timestamp(calledAt), taskId.toString(), task.version());
        if (updated != 1) {
            throw TaskLifecycleException.conflict("Task changed before it could be called");
        }
        insertTaskCalledEvent(task, actorId, calledAt);
        return requireTask(taskId).toView();
    }

    @Transactional
    public TaskView complete(UUID taskId, UUID actorId, int actualMinutes, Instant completedAt) {
        if (actualMinutes <= 0 || actualMinutes > MAX_ACTUAL_MINUTES) {
            throw TaskLifecycleException.invalidDuration();
        }
        TaskRecord task = requireTask(taskId);
        requireAssignee(task, actorId);
        if (!"IN_PROGRESS".equals(task.status())) {
            throw TaskLifecycleException.conflict("Only in-progress tasks can be completed");
        }
        Instant actualStart = completedAt.minusSeconds(actualMinutes * 60L);
        int updated = jdbc.update("""
                UPDATE tasks
                SET status = 'COMPLETED', completed_at = ?, completed_by_user_id = UUID_TO_BIN(?),
                    actual_start_at = ?, actual_end_at = ?, updated_at = ?, version = version + 1
                WHERE id = UUID_TO_BIN(?) AND version = ? AND status = 'IN_PROGRESS'
                """, timestamp(completedAt), actorId.toString(), timestamp(actualStart), timestamp(completedAt),
                timestamp(completedAt), taskId.toString(), task.version());
        if (updated != 1) {
            throw TaskLifecycleException.conflict("Task changed before it could be completed");
        }
        return requireTask(taskId).toView();
    }

    private TaskRecord requireTask(UUID taskId) {
        if (taskId == null) {
            throw TaskLifecycleException.notFound();
        }
        var rows = jdbc.query("""
                SELECT BIN_TO_UUID(id) id, ticket_number, category, system_name, process_number,
                       operation_start_at, operation_end_at, BIN_TO_UUID(creator_id) creator_id,
                       BIN_TO_UUID(current_assignee_id) assignee_id, status, called_at,
                       BIN_TO_UUID(called_by_user_id) called_by, actual_start_at, actual_end_at,
                       completed_at, BIN_TO_UUID(completed_by_user_id) completed_by, version
                FROM tasks WHERE id = UUID_TO_BIN(?)
                """, (result, row) -> new TaskRecord(
                        UUID.fromString(result.getString("id")), result.getString("ticket_number"),
                        TaskCategory.valueOf(result.getString("category")), result.getString("system_name"),
                        result.getString("process_number"), instant(result.getObject("operation_start_at", LocalDateTime.class)),
                        instant(result.getObject("operation_end_at", LocalDateTime.class)), UUID.fromString(result.getString("creator_id")),
                        UUID.fromString(result.getString("assignee_id")), result.getString("status"),
                        instant(result.getObject("called_at", LocalDateTime.class)), uuid(result.getString("called_by")),
                        instant(result.getObject("actual_start_at", LocalDateTime.class)), instant(result.getObject("actual_end_at", LocalDateTime.class)),
                        instant(result.getObject("completed_at", LocalDateTime.class)), uuid(result.getString("completed_by")), result.getLong("version")), taskId.toString());
        if (rows.isEmpty()) {
            throw TaskLifecycleException.notFound();
        }
        return rows.getFirst();
    }

    private void requireAssignee(TaskRecord task, UUID actorId) {
        if (actorId == null || !task.currentAssigneeId().equals(actorId)) {
            throw TaskLifecycleException.forbidden();
        }
    }

    private void insertTaskCalledEvent(TaskRecord task, UUID actorId, Instant calledAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("ticketNumber", task.ticketNumber());
        payload.put("systemName", task.systemName());
        payload.put("creatorId", task.creatorId());
        payload.put("assigneeId", task.currentAssigneeId());
        payload.put("calledByUserId", actorId);
        payload.put("calledAt", calledAt);
        try {
            String json = objectMapper.writeValueAsString(payload);
            jdbc.update("""
                    INSERT INTO notification_events (
                        id, event_type, aggregate_type, aggregate_id, recipient_user_id, payload,
                        status, retry_count, created_at, updated_at)
                    VALUES (UUID_TO_BIN(?), 'TASK_CALLED', 'TASK', UUID_TO_BIN(?), UUID_TO_BIN(?),
                        CAST(? AS JSON), 'NEW', 0, ?, ?)
                    """, UUID.randomUUID().toString(), task.id().toString(), task.creatorId().toString(), json,
                    timestamp(calledAt), timestamp(calledAt));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Task-called notification payload could not be serialized", exception);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private record TaskRecord(
            UUID id, String ticketNumber, TaskCategory category, String systemName, String processNumber,
            Instant operationStart, Instant operationEnd, UUID creatorId, UUID currentAssigneeId,
            String status, Instant calledAt, UUID calledByUserId, Instant actualStart, Instant actualEnd,
            Instant completedAt, UUID completedByUserId, long version) {
        TaskView toView() {
            Integer actualMinutes = actualStart == null || actualEnd == null ? null
                    : Math.toIntExact(java.time.Duration.between(actualStart, actualEnd).toMinutes());
            return new TaskView(id, ticketNumber, category, systemName, processNumber, operationStart,
                    operationEnd, creatorId, currentAssigneeId, status, calledAt, calledByUserId,
                    actualMinutes, completedAt, completedByUserId, version);
        }
    }
}
