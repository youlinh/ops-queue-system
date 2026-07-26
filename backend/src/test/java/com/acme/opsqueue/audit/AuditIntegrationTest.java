package com.acme.opsqueue.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.opsqueue.OpsQueueApplication;
import com.acme.opsqueue.identity.CurrentUser;
import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import com.acme.opsqueue.support.MySqlIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = OpsQueueApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditIntegrationTest extends MySqlIntegrationTest {
    @Autowired private AuditService audits;
    @Autowired private UserAccountRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    private UserAccount leader;
    private UserAccount operator;

    @BeforeEach
    void resetFixture() {
        truncateAuditLogs();
        users.findAll().stream()
                .filter(account -> !account.username().equals("test-bootstrap-leader"))
                .forEach(users::delete);
        leader = account("audit-leader", RoleName.LEADER);
        operator = account("audit-operator", RoleName.OPERATOR);
    }

    @Test
    void auditServiceWritesOneSafeImmutableCommandRow() {
        UUID objectId = UUID.randomUUID();

        audits.record(
                leader.id(),
                "ACCOUNT_PASSWORD_RESET",
                "USER",
                objectId,
                Map.of(),
                Map.of("passwordReset", true),
                "192.0.2.8",
                Instant.parse("2026-07-25T00:00:00Z"));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT BIN_TO_UUID(actor_id) actor_id, action, object_type,
                       BIN_TO_UUID(object_id) object_id, before_json, after_json,
                       source_ip, occurred_at
                FROM audit_logs
                """);
        assertThat(row.get("actor_id").toString()).isEqualToIgnoringCase(leader.id().toString());
        assertThat(row).containsEntry("action", "ACCOUNT_PASSWORD_RESET")
                .containsEntry("object_type", "USER")
                .containsEntry("source_ip", "192.0.2.8");
        assertThat(row.get("object_id").toString()).isEqualToIgnoringCase(objectId.toString());
        String summaries = row.get("before_json") + " " + row.get("after_json");
        assertThat(summaries).contains("passwordReset")
                .doesNotContain("Reporting-Test-Password-1", "passwordHash", "jwt", "cookie", "secret");
    }

    @Test
    void sourceIpFallsBackOutsideAnHttpRequest() throws InterruptedException {
        AtomicReference<String> sourceIp = new AtomicReference<>();
        Thread thread = Thread.startVirtualThread(
                () -> sourceIp.set(audits.currentSourceIp()));
        thread.join();

        assertThat(sourceIp.get()).isEqualTo("unknown");
    }

    @Test
    void databaseRejectsAuditUpdatesAndDeletes() {
        audits.record(
                leader.id(), "LOGIN_SUCCESS", "USER", leader.id(),
                Map.of(), Map.of("username", leader.username()),
                "192.0.2.8", Instant.now());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("UPDATE audit_logs SET source_ip = 'changed'"))
                .hasMessageContaining("audit_logs is append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("DELETE FROM audit_logs"))
                .hasMessageContaining("audit_logs is append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.execute("TRUNCATE TABLE audit_logs"))
                .rootCause()
                .hasMessageContaining("DROP command denied");
        assertThat(countAuditRows()).isEqualTo(1);
    }

    @Test
    void forbiddenSummaryKeysAreRejectedBeforePersistence() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> audits.record(
                        leader.id(), "ACCOUNT_CREATED", "USER", UUID.randomUUID(),
                        Map.of(), Map.of("password", "do-not-store"),
                        "192.0.2.8", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(countAuditRows()).isZero();
    }

    @Test
    void actionSummaryRejectsFieldsOutsideItsExplicitWhitelist() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> audits.record(
                        leader.id(), "LOGIN_SUCCESS", "USER", leader.id(),
                        Map.of(), Map.of("username", leader.username(), "note", "unrestricted"),
                        "192.0.2.8", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(countAuditRows()).isZero();
    }

    @Test
    void successfulLoginCreatesExactlyOneSafeAuditRow() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.44");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "audit-operator",
                                  "password": "Audit-Test-Password-1"
                                }
                                """))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT action, before_json, after_json, source_ip
                FROM audit_logs
                """);
        assertThat(countAuditRows()).isEqualTo(1);
        assertThat(row).containsEntry("action", "LOGIN_SUCCESS")
                .containsEntry("source_ip", "192.0.2.44");
        assertThat(row.get("before_json").toString()).isEqualTo("{}");
        assertThat(row.get("after_json").toString())
                .contains("\"username\": \"audit-operator\"")
                .doesNotContain("Audit-Test-Password-1", "password", "token", "cookie");
    }

    @Test
    void rejectedLoginDoesNotCreateSuccessAuditRow() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "audit-operator",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        assertThat(countAuditRows()).isZero();
    }

    @Test
    void leaderCanPageAndFilterNewestFirstWhileOperatorCannotList() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        audits.record(leader.id(), "TASK_CREATED", "TASK", first, Map.of(),
                Map.of("status", "PENDING"), "192.0.2.1",
                Instant.parse("2026-07-25T00:00:00Z"));
        audits.record(leader.id(), "TASK_CALLED", "TASK", second, Map.of("status", "PENDING"),
                Map.of("status", "IN_PROGRESS"), "192.0.2.2",
                Instant.parse("2026-07-25T00:01:00Z"));

        mvc.perform(get("/api/audit-logs")
                        .param("action", "TASK_CALLED")
                        .param("objectType", "TASK")
                        .param("actorId", leader.id().toString())
                        .param("page", "0")
                        .param("size", "1")
                        .with(authentication(auth(leader))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].objectId").value(second.toString()))
                .andExpect(jsonPath("$.content[0].action").value("TASK_CALLED"));

        mvc.perform(get("/api/audit-logs")
                        .with(authentication(auth(operator))))
                .andExpect(status().isForbidden());
    }

    @Test
    void failedCommandDoesNotCreateSuccessAuditRow() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> audits.record(
                        null, "TASK_CREATED", "TASK", UUID.randomUUID(),
                        Map.of(), Map.of(), "192.0.2.8", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(countAuditRows()).isZero();
    }

    private int countAuditRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class);
    }

    private UserAccount account(String username, RoleName role) {
        return users.saveAndFlush(UserAccount.create(
                username, username, passwords.encode("Audit-Test-Password-1"),
                Set.of(role), false));
    }

    private UsernamePasswordAuthenticationToken auth(UserAccount account) {
        CurrentUser principal = new CurrentUser(
                account.id(), account.username(), account.displayName(),
                account.roles(), false);
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role(account).name())));
    }

    private RoleName role(UserAccount account) {
        return account.roles().iterator().next();
    }
}
