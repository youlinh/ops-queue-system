package com.acme.opsqueue.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the notification outbox bounded: delivered (SENT) events older than
 * the retention window are deleted in small batches.
 */
@Component
@ConditionalOnProperty(
        name = "ops.notification.retention.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NotificationRetentionJob {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(NotificationRetentionJob.class);
    private static final int BATCH_SIZE = 5_000;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration retention;

    public NotificationRetentionJob(
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${ops.notification.retention.days:30}") int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalStateException(
                    "ops.notification.retention.days must be at least 1");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(
            initialDelayString = "${ops.notification.retention.initial-delay-ms:300000}",
            fixedDelayString = "${ops.notification.retention.delay-ms:21600000}")
    public void purge() {
        Timestamp cutoff = Timestamp.valueOf(LocalDateTime.ofInstant(
                clock.instant().minus(retention), ZoneOffset.UTC));
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.update("""
                    DELETE FROM notification_events
                    WHERE status = 'SENT' AND updated_at < ?
                    LIMIT %d
                    """.formatted(BATCH_SIZE), cutoff);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        if (total > 0) {
            LOGGER.info("Purged {} delivered notification events", total);
        }
    }
}
