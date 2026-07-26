package com.acme.opsqueue.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.opsqueue.OpsQueueApplication;
import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import com.acme.opsqueue.support.MySqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = OpsQueueApplication.class)
@ActiveProfiles("test")
class NotificationClaimServiceTest extends MySqlIntegrationTest {
    @Autowired private NotificationClaimService service;
    @Autowired private UserAccountRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcTemplate jdbc;

    private UserAccount developer;
    private UserAccount otherDeveloper;

    @BeforeEach
    void resetFixture() {
        jdbc.update("DELETE FROM notification_events");
        truncateAuditLogs();
        jdbc.update("DELETE FROM assignment_histories");
        jdbc.update("DELETE FROM tasks");
        users.findAll().stream()
                .filter(account -> !account.username().equals("test-bootstrap-leader"))
                .forEach(users::delete);
        developer = account("notify-dev");
        otherDeveloper = account("notify-dev-other");
    }

    @Test
    void claimReturnsOldestEventsForRecipientOnlyAndMarksThemSent() {
        Instant base = Instant.parse("2026-07-25T10:00:00Z");
        UUID newer = insertEvent(developer.id(), base.plusSeconds(60), "NEW", "OPS-2");
        UUID older = insertEvent(developer.id(), base, "NEW", "OPS-1");
        UUID foreign = insertEvent(otherDeveloper.id(), base, "NEW", "OPS-3");
        insertEvent(developer.id(), base, "SENT", "OPS-4");

        List<NotificationClaimService.ClaimedNotification> claimed =
                service.claimPending(developer.id(), base.plusSeconds(300));

        assertThat(claimed).extracting(
                        NotificationClaimService.ClaimedNotification::id)
                .containsExactly(older, newer);
        assertThat(claimed.getFirst().eventType()).isEqualTo("TASK_CALLED");
        assertThat(claimed.getFirst().payload())
                .containsEntry("ticketNumber", "OPS-1");
        assertThat(statusOf(older)).isEqualTo("SENT");
        assertThat(statusOf(newer)).isEqualTo("SENT");
        assertThat(statusOf(foreign)).isEqualTo("NEW");
        assertThat(service.claimPending(developer.id(), base.plusSeconds(600)))
                .isEmpty();
    }

    @Test
    void claimIsBoundedToBatchSize() {
        Instant base = Instant.parse("2026-07-25T10:00:00Z");
        for (int index = 0; index < NotificationClaimService.MAX_CLAIM_BATCH + 5; index++) {
            insertEvent(developer.id(), base.plusSeconds(index), "NEW", "OPS-" + index);
        }

        assertThat(service.claimPending(developer.id(), base.plusSeconds(900)))
                .hasSize(NotificationClaimService.MAX_CLAIM_BATCH);
        assertThat(service.claimPending(developer.id(), base.plusSeconds(901)))
                .hasSize(5);
    }

    @Test
    void purgeDeletesOnlyDeliveredEventsOlderThanRetention() {
        Instant now = Instant.parse("2026-07-25T10:00:00Z");
        UUID oldSent = insertEvent(
                developer.id(), now.minus(java.time.Duration.ofDays(40)), "SENT", "OPS-1");
        UUID oldNew = insertEvent(
                developer.id(), now.minus(java.time.Duration.ofDays(40)), "NEW", "OPS-2");
        UUID recentSent = insertEvent(
                developer.id(), now.minus(java.time.Duration.ofDays(5)), "SENT", "OPS-3");

        new NotificationRetentionJob(
                jdbc, Clock.fixed(now, ZoneOffset.UTC), 30).purge();

        assertThat(exists(oldSent)).isFalse();
        assertThat(exists(oldNew)).isTrue();
        assertThat(exists(recentSent)).isTrue();
    }

    private UserAccount account(String username) {
        return users.save(UserAccount.create(
                username,
                username,
                passwords.encode("Fixture-Password-1!"),
                Set.of(RoleName.DEVELOPER),
                false));
    }

    private UUID insertEvent(
            UUID recipientId, Instant at, String status, String ticketNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_events (
                    id, event_type, aggregate_type, aggregate_id, recipient_user_id,
                    payload, status, retry_count, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), 'TASK_CALLED', 'TASK', UUID_TO_BIN(?),
                    UUID_TO_BIN(?), CAST(? AS JSON), ?, 0, ?, ?)
                """,
                id.toString(),
                UUID.randomUUID().toString(),
                recipientId.toString(),
                "{\"ticketNumber\":\"" + ticketNumber + "\",\"systemName\":\"计费系统\"}",
                status,
                timestamp(at),
                timestamp(at));
        return id;
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM notification_events WHERE id = UUID_TO_BIN(?)",
                String.class,
                id.toString());
    }

    private boolean exists(UUID id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_events WHERE id = UUID_TO_BIN(?)",
                Integer.class,
                id.toString());
        return count != null && count > 0;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
