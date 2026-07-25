package com.acme.opsqueue.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_events")
public class NotificationEvent {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, length = 64)
    private String aggregateType;

    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID aggregateId;

    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID recipientUserId;

    @Column(nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private int retryCount;

    private String lastError;
    private Instant nextAttemptAt;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected NotificationEvent() {
    }
}
