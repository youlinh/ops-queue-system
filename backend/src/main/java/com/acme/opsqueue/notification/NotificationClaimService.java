package com.acme.opsqueue.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers outbox rows to their recipient. A claim atomically returns the
 * oldest NEW events for the caller and marks them SENT, so each event is
 * handed to at most one browser poll (SKIP LOCKED keeps concurrent tabs from
 * blocking each other).
 */
@Service
public class NotificationClaimService {
    static final int MAX_CLAIM_BATCH = 20;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NotificationClaimService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<ClaimedNotification> claimPending(UUID recipientId, Instant at) {
        if (recipientId == null) {
            return List.of();
        }
        List<ClaimedNotification> claimed = jdbc.query("""
                SELECT BIN_TO_UUID(id) id, event_type, payload, created_at
                FROM notification_events
                WHERE recipient_user_id = UUID_TO_BIN(?)
                  AND status = 'NEW'
                ORDER BY created_at ASC, id ASC
                LIMIT %d
                FOR UPDATE SKIP LOCKED
                """.formatted(MAX_CLAIM_BATCH),
                (result, row) -> new ClaimedNotification(
                        UUID.fromString(result.getString("id")),
                        result.getString("event_type"),
                        payload(result.getString("payload")),
                        result.getObject("created_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC)),
                recipientId.toString());
        if (claimed.isEmpty()) {
            return List.of();
        }

        StringBuilder placeholders = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        parameters.add(timestamp(at == null ? Instant.now() : at));
        for (ClaimedNotification notification : claimed) {
            if (!placeholders.isEmpty()) {
                placeholders.append(", ");
            }
            placeholders.append("UUID_TO_BIN(?)");
            parameters.add(notification.id().toString());
        }
        int updated = jdbc.update("""
                UPDATE notification_events
                SET status = 'SENT', updated_at = ?
                WHERE id IN (%s) AND status = 'NEW'
                """.formatted(placeholders),
                parameters.toArray());
        if (updated != claimed.size()) {
            throw new IllegalStateException(
                    "Claimed notification rows changed while being marked as sent");
        }
        return claimed;
    }

    private Map<String, Object> payload(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored notification payload is invalid", exception);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    public record ClaimedNotification(
            UUID id,
            String eventType,
            Map<String, Object> payload,
            Instant createdAt) {
    }
}
