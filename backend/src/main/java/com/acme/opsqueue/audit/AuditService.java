package com.acme.opsqueue.audit;

import com.acme.opsqueue.identity.ClientIpResolver;
import com.acme.opsqueue.identity.CurrentUser;
import com.acme.opsqueue.identity.RoleName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_FILTER_LENGTH = 64;
    private static final Set<String> FORBIDDEN_KEY_PARTS =
            Set.of("password", "hash", "token", "cookie", "secret", "csrf", "jwt");
    private static final Map<String, SummaryPolicy> SUMMARY_POLICIES = Map.ofEntries(
            Map.entry("LOGIN_SUCCESS",
                    policy(Set.of(), Set.of("username"))),
            Map.entry("ACCOUNT_CREATED",
                    policy(Set.of(), Set.of("username", "enabled", "roles"))),
            Map.entry("ACCOUNT_DISABLED",
                    policy(Set.of("enabled"), Set.of("enabled"))),
            Map.entry("ACCOUNT_PASSWORD_RESET",
                    policy(Set.of(), Set.of("passwordReset"))),
            Map.entry("ACCOUNT_ROLES_CHANGED",
                    policy(Set.of(), Set.of("roles"))),
            Map.entry("ROSTER_CONFIRMED",
                    policy(Set.of("status"), Set.of("status"))),
            Map.entry("TASK_CREATED",
                    policy(Set.of(),
                            Set.of("ticketNumber", "assigneeId", "status", "assignmentRule"))),
            Map.entry("TASK_CALLED",
                    policy(Set.of("status"), Set.of("status", "assigneeId"))),
            Map.entry("TASK_COMPLETED",
                    policy(Set.of("status"),
                            Set.of("status", "assigneeId", "actualMinutes"))),
            Map.entry("TASK_TRANSFERRED",
                    policy(Set.of("assigneeId"), Set.of("assigneeId"))),
            Map.entry("TASK_LEADER_ADJUSTED",
                    policy(Set.of("assigneeId"), Set.of("assigneeId"))),
            Map.entry("UNAVAILABILITY_CREATED",
                    policy(Set.of(), Set.of("date"))),
            Map.entry("UNAVAILABILITY_REMOVED",
                    policy(Set.of("date"), Set.of())),
            Map.entry("REDISTRIBUTION_EXECUTED",
                    policy(Set.of(),
                            Set.of(
                                    "date", "taskCount", "processedCount",
                                    "successCount", "failureCount", "startedAt"))),
            Map.entry("REDISTRIBUTION_INTERRUPTED",
                    policy(Set.of(),
                            Set.of(
                                    "date", "taskCount", "processedCount",
                                    "successCount", "failureCount", "startedAt"))));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIps;
    private final ObjectProvider<HttpServletRequest> requests;

    public AuditService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ClientIpResolver clientIps,
            ObjectProvider<HttpServletRequest> requests) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clientIps = clientIps;
        this.requests = requests;
    }

    @Transactional
    public void record(
            UUID actorId,
            String action,
            String objectType,
            UUID objectId,
            Map<String, ?> before,
            Map<String, ?> after,
            String sourceIp,
            Instant occurredAt) {
        requireIdentity(actorId, "Actor is required");
        requireIdentity(objectId, "Object id is required");
        String normalizedAction = bounded(action, "Action");
        String normalizedObjectType = bounded(objectType, "Object type");
        Instant at = occurredAt == null ? Instant.now() : occurredAt;
        Map<String, ?> safeBefore = before == null ? Map.of() : before;
        Map<String, ?> safeAfter = after == null ? Map.of() : after;
        validateSummaryKeys(normalizedAction, safeBefore, safeAfter);
        rejectForbiddenKeys(safeBefore);
        rejectForbiddenKeys(safeAfter);
        jdbc.update("""
                INSERT INTO audit_logs (
                    id, actor_id, action, object_type, object_id,
                    before_json, after_json, source_ip, occurred_at)
                VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, UUID_TO_BIN(?),
                    CAST(? AS JSON), CAST(? AS JSON), ?, ?)
                """,
                UUID.randomUUID().toString(),
                actorId.toString(),
                normalizedAction,
                normalizedObjectType,
                objectId.toString(),
                json(safeBefore),
                json(safeAfter),
                normalizeSourceIp(sourceIp),
                timestamp(at));
    }

    public void recordCurrentRequest(
            UUID actorId,
            String action,
            String objectType,
            UUID objectId,
            Map<String, ?> before,
            Map<String, ?> after,
            Instant occurredAt) {
        record(
                actorId, action, objectType, objectId,
                before, after, currentSourceIp(), occurredAt);
    }

    public String currentSourceIp() {
        try {
            HttpServletRequest request = requests.getIfAvailable();
            return request == null ? "unknown" : clientIps.resolve(request);
        } catch (IllegalStateException exception) {
            return "unknown";
        }
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> search(
            CurrentUser requester,
            String action,
            String objectType,
            UUID objectId,
            UUID actorId,
            Instant from,
            Instant to,
            int page,
            int size) {
        requireLeader(requester);
        if (from != null && to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("Audit end time must be after start time");
        }
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        List<Object> parameters = new ArrayList<>();
        String where = where(action, objectType, objectId, actorId, from, to, parameters);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs " + where,
                Long.class,
                parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(normalizedSize);
        pageParameters.add((long) normalizedPage * normalizedSize);
        List<AuditLog> content = jdbc.query("""
                SELECT BIN_TO_UUID(id) id, BIN_TO_UUID(actor_id) actor_id,
                       action, object_type, BIN_TO_UUID(object_id) object_id,
                       before_json, after_json, source_ip, occurred_at
                FROM audit_logs
                """ + where + """
                ORDER BY occurred_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                (result, row) -> new AuditLog(
                        UUID.fromString(result.getString("id")),
                        UUID.fromString(result.getString("actor_id")),
                        result.getString("action"),
                        result.getString("object_type"),
                        UUID.fromString(result.getString("object_id")),
                        map(result.getString("before_json")),
                        map(result.getString("after_json")),
                        result.getString("source_ip"),
                        result.getObject("occurred_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC)),
                pageParameters.toArray());
        return new PageImpl<>(
                content,
                PageRequest.of(normalizedPage, normalizedSize),
                total == null ? 0 : total);
    }

    private String where(
            String action,
            String objectType,
            UUID objectId,
            UUID actorId,
            Instant from,
            Instant to,
            List<Object> parameters) {
        StringBuilder sql = new StringBuilder("WHERE 1=1 ");
        if (action != null && !action.isBlank()) {
            sql.append("AND action = ? ");
            parameters.add(bounded(action, "Action"));
        }
        if (objectType != null && !objectType.isBlank()) {
            sql.append("AND object_type = ? ");
            parameters.add(bounded(objectType, "Object type"));
        }
        if (objectId != null) {
            sql.append("AND object_id = UUID_TO_BIN(?) ");
            parameters.add(objectId.toString());
        }
        if (actorId != null) {
            sql.append("AND actor_id = UUID_TO_BIN(?) ");
            parameters.add(actorId.toString());
        }
        if (from != null) {
            sql.append("AND occurred_at >= ? ");
            parameters.add(timestamp(from));
        }
        if (to != null) {
            sql.append("AND occurred_at < ? ");
            parameters.add(timestamp(to));
        }
        return sql.toString();
    }

    private void requireLeader(CurrentUser requester) {
        if (requester == null || !requester.roles().contains(RoleName.LEADER)) {
            throw new AccessDeniedException("Leader role is required");
        }
    }

    private void rejectForbiddenKeys(Map<String, ?> summary) {
        summary.forEach((key, value) -> {
            String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
            boolean explicitlySafePasswordFact =
                    normalized.equals("passwordreset")
                            || normalized.equals("passwordchanged");
            if (!explicitlySafePasswordFact
                    && FORBIDDEN_KEY_PARTS.stream().anyMatch(normalized::contains)) {
                throw new IllegalArgumentException("Audit summary contains a forbidden field");
            }
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, ?> typed = (Map<String, ?>) nested;
                rejectForbiddenKeys(typed);
            }
        });
    }

    private void validateSummaryKeys(
            String action, Map<String, ?> before, Map<String, ?> after) {
        SummaryPolicy policy = SUMMARY_POLICIES.get(action);
        if (policy == null) {
            throw new IllegalArgumentException("Audit action is not approved");
        }
        if (!policy.before().containsAll(before.keySet())
                || !policy.after().containsAll(after.keySet())) {
            throw new IllegalArgumentException(
                    "Audit summary contains a field outside the action whitelist");
        }
    }

    private String bounded(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > MAX_FILTER_LENGTH) {
            throw new IllegalArgumentException(label + " is too long");
        }
        return normalized;
    }

    private void requireIdentity(UUID value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }

    private String normalizeSourceIp(String sourceIp) {
        if (sourceIp == null || sourceIp.isBlank()) {
            return "unknown";
        }
        String normalized = sourceIp.trim();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Audit summary is not serializable", exception);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored audit summary is invalid", exception);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private static SummaryPolicy policy(Set<String> before, Set<String> after) {
        return new SummaryPolicy(before, after);
    }

    private record SummaryPolicy(Set<String> before, Set<String> after) {
    }
}
