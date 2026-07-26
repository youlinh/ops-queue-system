package com.acme.opsqueue.task;

import com.acme.opsqueue.identity.CurrentUser;
import com.acme.opsqueue.identity.RoleName;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TaskQueryService {
    private static final Set<String> VALID_STATUSES =
            Set.of("PENDING", "IN_PROGRESS", "COMPLETED");
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final int DEFAULT_SUGGESTION_LIMIT = 10;
    private static final int MAX_SUGGESTION_LIMIT = 20;

    private final JdbcTemplate jdbc;

    public TaskQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TaskPage<TaskRow> search(TaskQuery query, CurrentUser currentUser) {
        int page = normalizePage(query.page());
        int size = normalizeSize(query.size());
        List<Object> params = new ArrayList<>();
        String where = whereClause(query, currentUser, params);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks t " + where,
                Long.class,
                params.toArray());
        List<Object> rowParams = new ArrayList<>(params);
        rowParams.add(size);
        rowParams.add(page * size);
        List<TaskRow> content = jdbc.query("""
                        SELECT %s
                        FROM tasks t
                        JOIN users creator ON creator.id = t.creator_id
                        JOIN users assignee ON assignee.id = t.current_assignee_id
                        %s
                        %s
                        LIMIT ? OFFSET ?
                        """.formatted(rowColumns(), where, orderBy(query.sort())),
                (result, row) -> mapTaskRow(result, currentUser),
                rowParams.toArray());
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new TaskPage<>(content, page, size, total, totalPages);
    }

    public TaskDetailView detail(UUID id, CurrentUser currentUser) {
        List<Object> params = new ArrayList<>();
        params.add(id.toString());
        String visibility = visibilityPredicate(currentUser, params);
        List<TaskDetailView> rows = jdbc.query("""
                        SELECT %s,
                               BIN_TO_UUID(t.called_by_user_id) called_by_user_id,
                               BIN_TO_UUID(t.completed_by_user_id) completed_by_user_id,
                               t.called_at, t.completed_at, t.version
                        FROM tasks t
                        JOIN users creator ON creator.id = t.creator_id
                        JOIN users assignee ON assignee.id = t.current_assignee_id
                        WHERE t.id = UUID_TO_BIN(?) %s
                        """.formatted(rowColumns(), visibility),
                (result, row) -> {
                    TaskRow task = mapTaskRow(result, currentUser);
                    return new TaskDetailView(
                            task.id(),
                            task.ticketNumber(),
                            task.category(),
                            task.systemName(),
                            task.processNumber(),
                            task.operationStart(),
                            task.operationEnd(),
                            task.creatorId(),
                            task.creatorName(),
                            task.currentAssigneeId(),
                            task.currentAssigneeName(),
                            task.status(),
                            task.estimatedMinutes(),
                            task.actualMinutes(),
                            task.assignmentRule(),
                            task.canCall(),
                            task.canComplete(),
                            task.canTransfer(),
                            task.createdAt(),
                            instant(result.getObject("called_at", LocalDateTime.class)),
                            uuid(result.getString("called_by_user_id")),
                            instant(result.getObject("completed_at", LocalDateTime.class)),
                            uuid(result.getString("completed_by_user_id")),
                            result.getLong("version"),
                            timeline(id));
                },
                params.toArray());
        if (rows.isEmpty()) {
            throw TaskLifecycleException.notFound();
        }
        return rows.getFirst();
    }

    public List<String> systemNames(String query, Integer requestedLimit, CurrentUser currentUser) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() < 2) {
            return List.of();
        }
        int limit = requestedLimit == null ? DEFAULT_SUGGESTION_LIMIT : requestedLimit;
        if (limit <= 0) {
            throw TaskLifecycleException.invalidRequest("Suggestion limit must be positive");
        }
        limit = Math.min(limit, MAX_SUGGESTION_LIMIT);
        List<Object> params = new ArrayList<>();
        params.add("%" + escapeLike(normalized) + "%");
        String visibility = visibilityPredicate(currentUser, params);
        params.add(limit);
        return jdbc.query("""
                        SELECT DISTINCT t.system_name
                        FROM tasks t
                        WHERE t.system_name LIKE ? ESCAPE '!' %s
                        ORDER BY t.system_name ASC
                        LIMIT ?
                        """.formatted(visibility),
                (result, row) -> result.getString(1),
                params.toArray());
    }

    private String whereClause(TaskQuery query, CurrentUser currentUser, List<Object> params) {
        List<String> predicates = new ArrayList<>();
        if (query.operationDate() != null) {
            predicates.add("t.operation_date = ?");
            params.add(query.operationDate());
        }
        if (query.category() != null) {
            predicates.add("t.category = ?");
            params.add(query.category().name());
        }
        if (query.status() != null && !query.status().isBlank()) {
            String status = query.status().trim().toUpperCase(Locale.ROOT);
            if (!VALID_STATUSES.contains(status)) {
                throw TaskLifecycleException.invalidRequest("Unsupported task status");
            }
            predicates.add("t.status = ?");
            params.add(status);
        }
        if (query.assigneeId() != null) {
            predicates.add("t.current_assignee_id = UUID_TO_BIN(?)");
            params.add(query.assigneeId().toString());
        }
        if (query.systemName() != null && !query.systemName().trim().isEmpty()) {
            predicates.add("t.system_name LIKE ? ESCAPE '!'");
            params.add("%" + escapeLike(query.systemName().trim()) + "%");
        }
        if (canSeeAllTasks(currentUser)) {
            if (query.creatorId() != null) {
                predicates.add("t.creator_id = UUID_TO_BIN(?)");
                params.add(query.creatorId().toString());
            }
        } else {
            predicates.add("t.creator_id = UUID_TO_BIN(?)");
            params.add(currentUser.id().toString());
        }
        return predicates.isEmpty() ? "" : "WHERE " + String.join(" AND ", predicates);
    }

    private String visibilityPredicate(CurrentUser currentUser, List<Object> params) {
        if (canSeeAllTasks(currentUser)) {
            return "";
        }
        params.add(currentUser.id().toString());
        return " AND t.creator_id = UUID_TO_BIN(?)";
    }

    private boolean canSeeAllTasks(CurrentUser user) {
        return user.roles().contains(RoleName.OPERATOR) || user.roles().contains(RoleName.LEADER);
    }

    private int normalizePage(int page) {
        if (page < 0) {
            throw TaskLifecycleException.invalidRequest("Page must not be negative");
        }
        return page;
    }

    private int normalizeSize(int size) {
        int normalized = size <= 0 ? DEFAULT_SIZE : size;
        return Math.min(normalized, MAX_SIZE);
    }

    private String orderBy(String sort) {
        return switch (sort == null ? "" : sort) {
            case "operationStart,desc" -> "ORDER BY t.operation_start_at DESC, t.ticket_number ASC";
            case "ticketNumber,asc" -> "ORDER BY t.ticket_number ASC";
            case "ticketNumber,desc" -> "ORDER BY t.ticket_number DESC";
            case "createdAt,asc" -> "ORDER BY t.created_at ASC, t.ticket_number ASC";
            case "createdAt,desc" -> "ORDER BY t.created_at DESC, t.ticket_number ASC";
            default -> "ORDER BY t.operation_start_at ASC, t.ticket_number ASC";
        };
    }

    private String rowColumns() {
        return """
                BIN_TO_UUID(t.id) id, t.ticket_number, t.category, t.system_name,
                t.process_number, t.operation_start_at, t.operation_end_at,
                BIN_TO_UUID(t.creator_id) creator_id, creator.display_name creator_name,
                BIN_TO_UUID(t.current_assignee_id) assignee_id,
                assignee.display_name assignee_name, t.status, t.estimated_minutes,
                CASE
                    WHEN t.actual_start_at IS NOT NULL AND t.actual_end_at IS NOT NULL
                    THEN TIMESTAMPDIFF(MINUTE, t.actual_start_at, t.actual_end_at)
                    ELSE NULL
                END actual_minutes,
                t.auto_assignment_rule, t.created_at
                """;
    }

    private TaskRow mapTaskRow(java.sql.ResultSet result, CurrentUser currentUser)
            throws java.sql.SQLException {
        UUID assigneeId = UUID.fromString(result.getString("assignee_id"));
        String status = result.getString("status");
        boolean assignee = assigneeId.equals(currentUser.id());
        return new TaskRow(
                UUID.fromString(result.getString("id")),
                result.getString("ticket_number"),
                TaskCategory.valueOf(result.getString("category")),
                result.getString("system_name"),
                result.getString("process_number"),
                instant(result.getObject("operation_start_at", LocalDateTime.class)),
                instant(result.getObject("operation_end_at", LocalDateTime.class)),
                UUID.fromString(result.getString("creator_id")),
                result.getString("creator_name"),
                assigneeId,
                result.getString("assignee_name"),
                status,
                result.getInt("estimated_minutes"),
                integer(result.getObject("actual_minutes")),
                result.getString("auto_assignment_rule"),
                assignee && "PENDING".equals(status),
                assignee && "IN_PROGRESS".equals(status),
                assignee && !"COMPLETED".equals(status),
                instant(result.getObject("created_at", LocalDateTime.class)));
    }

    private List<TaskDetailView.AssignmentTimelineEntry> timeline(UUID taskId) {
        return jdbc.query("""
                        SELECT ah.assignment_type,
                               BIN_TO_UUID(ah.old_assignee_id) old_assignee_id,
                               old_user.display_name old_assignee_name,
                               BIN_TO_UUID(ah.new_assignee_id) new_assignee_id,
                               new_user.display_name new_assignee_name,
                               ah.assignment_rule, ah.reason,
                               BIN_TO_UUID(ah.actor_id) actor_id,
                               actor.display_name actor_name,
                               ah.assigned_at
                        FROM assignment_histories ah
                        LEFT JOIN users old_user ON old_user.id = ah.old_assignee_id
                        JOIN users new_user ON new_user.id = ah.new_assignee_id
                        JOIN users actor ON actor.id = ah.actor_id
                        WHERE ah.task_id = UUID_TO_BIN(?)
                        ORDER BY ah.assigned_at ASC, ah.id ASC
                        """,
                (result, row) -> new TaskDetailView.AssignmentTimelineEntry(
                        result.getString("assignment_type"),
                        uuid(result.getString("old_assignee_id")),
                        result.getString("old_assignee_name"),
                        UUID.fromString(result.getString("new_assignee_id")),
                        result.getString("new_assignee_name"),
                        result.getString("assignment_rule"),
                        result.getString("reason"),
                        UUID.fromString(result.getString("actor_id")),
                        result.getString("actor_name"),
                        instant(result.getObject("assigned_at", LocalDateTime.class))),
                taskId.toString());
    }

    private String escapeLike(String value) {
        return value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    public record TaskRow(
            UUID id,
            String ticketNumber,
            TaskCategory category,
            String systemName,
            String processNumber,
            Instant operationStart,
            Instant operationEnd,
            UUID creatorId,
            String creatorName,
            UUID currentAssigneeId,
            String currentAssigneeName,
            String status,
            int estimatedMinutes,
            Integer actualMinutes,
            String assignmentRule,
            boolean canCall,
            boolean canComplete,
            boolean canTransfer,
            Instant createdAt) {
    }
}
