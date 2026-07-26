package com.acme.opsqueue.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.opsqueue.OpsQueueApplication;
import com.acme.opsqueue.identity.CurrentUser;
import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import com.acme.opsqueue.support.MySqlIntegrationTest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = OpsQueueApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TaskLifecycleServiceTest.FixedClockConfiguration.class)
class TaskLifecycleServiceTest extends MySqlIntegrationTest {
    private static final Instant CALLED_AT = Instant.parse("2026-07-25T04:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-07-25T06:00:00Z");

    @Autowired private TaskLifecycleService service;
    @Autowired private UserAccountRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private DataSource dataSource;
    @MockitoSpyBean private ObjectMapper objectMapper;

    private UserAccount creator;
    private UserAccount assignee;
    private UserAccount other;

    @BeforeEach
    void resetFixture() {
        reset(objectMapper);
        truncateAuditLogs();
        jdbc.update("DELETE FROM notification_events");
        jdbc.update("DELETE FROM assignment_histories");
        jdbc.update("DELETE FROM tasks");
        users.findAll().stream()
                .filter(account -> !account.username().equals("test-bootstrap-leader"))
                .forEach(users::delete);
        creator = account("lifecycle-creator", RoleName.DEVELOPER);
        assignee = account("lifecycle-assignee", RoleName.OPERATOR);
        other = account("lifecycle-other", RoleName.OPERATOR);
    }

    @Test
    void assigneeCallingPendingTaskTransitionsItAndWritesOneCreatorOutboxEvent() {
        UUID taskId = seedTask("PENDING", 0);

        TaskView result = service.call(taskId, assignee.id(), CALLED_AT);

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.calledAt()).isEqualTo(CALLED_AT);
        assertThat(result.calledByUserId()).isEqualTo(assignee.id());
        assertThat(result.version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = UUID_TO_BIN(?)", String.class, taskId.toString()))
                .isEqualTo("IN_PROGRESS");
        assertThat(count("notification_events")).isEqualTo(1);
        var event = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, BIN_TO_UUID(aggregate_id) aggregate_id,
                       BIN_TO_UUID(recipient_user_id) recipient, payload, status, retry_count
                FROM notification_events
                """);
        assertThat(event).containsEntry("event_type", "TASK_CALLED")
                .containsEntry("aggregate_type", "TASK")
                .containsEntry("status", "NEW")
                .containsEntry("retry_count", 0);
        assertThat((String) event.get("aggregate_id")).isEqualToIgnoringCase(taskId.toString());
        assertThat((String) event.get("recipient")).isEqualToIgnoringCase(creator.id().toString());
        assertThat((String) event.get("payload")).contains(
                taskId.toString(), "OPS-", "Billing", creator.id().toString(),
                assignee.id().toString(), "2026-07-25T04:00:00Z");
    }

    @Test
    void callingIsForbiddenForNonAssignee() {
        UUID pending = seedTask("PENDING", 0);
        assertThatThrownBy(() -> service.call(pending, other.id(), CALLED_AT))
                .isInstanceOf(TaskLifecycleException.class)
                .satisfies(error -> assertThat(((TaskLifecycleException) error).reason())
                        .isEqualTo(TaskLifecycleException.Reason.FORBIDDEN));
        assertThat(statusInDatabase(pending)).isEqualTo("PENDING");
        assertThat(count("notification_events")).isZero();
    }

    @Test
    void callingMissingTaskReportsNotFound() {
        assertThatThrownBy(() -> service.call(UUID.randomUUID(), assignee.id(), CALLED_AT))
                .isInstanceOf(TaskLifecycleException.class)
                .satisfies(error -> assertThat(((TaskLifecycleException) error).reason())
                        .isEqualTo(TaskLifecycleException.Reason.NOT_FOUND));
        assertThat(count("notification_events")).isZero();
    }

    @Test
    void callingNonPendingTaskReportsConflictAndWritesNoOutbox() {
        UUID inProgress = seedTask("IN_PROGRESS", 0);
        assertThatThrownBy(() -> service.call(inProgress, assignee.id(), CALLED_AT))
                .isInstanceOf(TaskLifecycleException.class)
                .satisfies(error -> assertThat(((TaskLifecycleException) error).reason())
                        .isEqualTo(TaskLifecycleException.Reason.CONFLICT));
        assertThat(count("notification_events")).isZero();
    }

    @Test
    void assigneeCompletingInProgressTaskPersistsDurationAndCompletionEvidence() {
        UUID taskId = seedTask("IN_PROGRESS", 3);

        TaskView result = service.complete(taskId, assignee.id(), 90, COMPLETED_AT);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.actualMinutes()).isEqualTo(90);
        assertThat(result.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(result.completedByUserId()).isEqualTo(assignee.id());
        assertThat(result.version()).isEqualTo(4);
        assertThat(jdbc.queryForMap("""
                SELECT status, version,
                       DATE_FORMAT(actual_start_at, '%Y-%m-%dT%H:%i:%sZ') actual_start,
                       DATE_FORMAT(actual_end_at, '%Y-%m-%dT%H:%i:%sZ') actual_end,
                       BIN_TO_UUID(completed_by_user_id) completed_by
                FROM tasks WHERE id = UUID_TO_BIN(?)
                """, taskId.toString()))
                .containsEntry("status", "COMPLETED")
                .containsEntry("version", 4L)
                .containsEntry("actual_start", "2026-07-25T04:30:00Z")
                .containsEntry("actual_end", "2026-07-25T06:00:00Z")
                .containsEntry("completed_by", assignee.id().toString());
    }

    @Test
    void completingWithNonPositiveMinutesReportsUnprocessableAndLeavesTaskUnchanged() {
        UUID inProgress = seedTask("IN_PROGRESS", 0);
        assertThatThrownBy(() -> service.complete(inProgress, assignee.id(), 0, COMPLETED_AT))
                .isInstanceOf(TaskLifecycleException.class)
                .satisfies(error -> assertThat(((TaskLifecycleException) error).reason())
                        .isEqualTo(TaskLifecycleException.Reason.INVALID_DURATION));
        assertThatThrownBy(() -> service.complete(inProgress, assignee.id(), -1, COMPLETED_AT))
                .isInstanceOf(TaskLifecycleException.class)
                .satisfies(error -> assertThat(((TaskLifecycleException) error).reason())
                        .isEqualTo(TaskLifecycleException.Reason.INVALID_DURATION));
        assertThat(statusInDatabase(inProgress)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM tasks WHERE id = UUID_TO_BIN(?)", Timestamp.class, inProgress.toString())).isNull();
    }

    @Test
    void completingIsForbiddenForNonAssignee() {
        UUID inProgress = seedTask("IN_PROGRESS", 0);
        assertThatThrownBy(() -> service.complete(inProgress, other.id(), 10, COMPLETED_AT))
                .isInstanceOf(TaskLifecycleException.class)
                .satisfies(error -> assertThat(((TaskLifecycleException) error).reason())
                        .isEqualTo(TaskLifecycleException.Reason.FORBIDDEN));
        assertThat(statusInDatabase(inProgress)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void completingPendingTaskReportsConflict() {
        UUID pending = seedTask("PENDING", 0);
        assertThatThrownBy(() -> service.complete(pending, assignee.id(), 10, COMPLETED_AT))
                .isInstanceOf(TaskLifecycleException.class)
                .satisfies(error -> assertThat(((TaskLifecycleException) error).reason())
                        .isEqualTo(TaskLifecycleException.Reason.CONFLICT));
        assertThat(statusInDatabase(pending)).isEqualTo("PENDING");
    }

    @Test
    void completedTaskCannotBeCompletedOrCalledAgain() {
        UUID completed = seedTask("COMPLETED", 0);
        assertThatThrownBy(() -> service.complete(completed, assignee.id(), 10, COMPLETED_AT))
                .isInstanceOf(TaskLifecycleException.class)
                .satisfies(error -> assertThat(((TaskLifecycleException) error).reason())
                        .isEqualTo(TaskLifecycleException.Reason.CONFLICT));
        assertThatThrownBy(() -> service.call(completed, assignee.id(), CALLED_AT))
                .isInstanceOf(TaskLifecycleException.class)
                .satisfies(error -> assertThat(((TaskLifecycleException) error).reason())
                        .isEqualTo(TaskLifecycleException.Reason.CONFLICT));
        assertThat(statusInDatabase(completed)).isEqualTo("COMPLETED");
    }

    @Test
    void lifecycleEndpointsUseAuthenticatedAssigneeAndRequireCsrf() throws Exception {
        UUID taskId = seedTask("PENDING", 0);
        CurrentUser principal = currentUser(assignee);

        mvc.perform(post("/api/tasks/{id}/call", taskId)
                        .with(authentication(authenticationFor(principal))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/{id}/call", taskId)
                        .with(csrf())
                        .with(authentication(authenticationFor(principal)))
                        .contentType(APPLICATION_JSON)
                        .content("{\"calledByUserId\":\"" + other.id() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.calledByUserId").value(assignee.id().toString()));
        mvc.perform(post("/api/tasks/{id}/complete", taskId)
                        .with(csrf())
                        .with(authentication(authenticationFor(principal)))
                        .contentType(APPLICATION_JSON)
                        .content("{\"actualMinutes\":30,\"completedByUserId\":\"" + other.id() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedByUserId").value(assignee.id().toString()))
                .andExpect(jsonPath("$.actualMinutes").value(30));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'TASK_CALLED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'TASK_COMPLETED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT BIN_TO_UUID(actor_id) actor_id, source_ip
                FROM audit_logs
                WHERE action = 'TASK_CALLED'
                """))
                .containsEntry("actor_id", assignee.id().toString())
                .containsEntry("source_ip", "127.0.0.1");
    }

    @Test
    void lifecycleEndpointsMapMissingForbiddenConflictAndInvalidDuration() throws Exception {
        CurrentUser assigneePrincipal = currentUser(assignee);
        mvc.perform(post("/api/tasks/{id}/call", UUID.randomUUID())
                        .with(csrf()).with(authentication(authenticationFor(assigneePrincipal))))
                .andExpect(status().isNotFound());
        UUID pending = seedTask("PENDING", 0);
        mvc.perform(post("/api/tasks/{id}/call", pending)
                        .with(csrf()).with(authentication(authenticationFor(currentUser(other)))) )
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/{id}/complete", pending)
                        .with(csrf()).with(authentication(authenticationFor(assigneePrincipal)))
                        .contentType(APPLICATION_JSON).content("{\"actualMinutes\":0}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/tasks/{id}/complete", pending)
                        .with(csrf()).with(authentication(authenticationFor(assigneePrincipal)))
                        .contentType(APPLICATION_JSON).content("{\"actualMinutes\":10}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_logs
                WHERE action IN ('TASK_CALLED', 'TASK_COMPLETED')
                """,
                Integer.class)).isZero();
    }

    @Test
    void outboxFailureRollsBackTheCallUpdate() throws Exception {
        UUID taskId = seedTask("PENDING", 0);
        doThrow(new JsonProcessingException("forced serialization failure") { })
                .when(objectMapper).writeValueAsString(any());

        assertThatThrownBy(() -> service.call(taskId, assignee.id(), CALLED_AT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(statusInDatabase(taskId)).isEqualTo("PENDING");
        assertThat(count("notification_events")).isZero();
    }

    @Test
    void concurrentCallsMapOptimisticContentionToConflictAndWriteOnlyOneOutboxEvent() throws Exception {
        UUID taskId = seedTask("PENDING", 0);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (var lockConnection = dataSource.getConnection();
                var statement = lockConnection.prepareStatement(
                        "SELECT id FROM tasks WHERE id = UUID_TO_BIN(?) FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            statement.setString(1, taskId.toString());
            statement.executeQuery();
            Callable<String> attemptCall = () -> {
                try {
                    service.call(taskId, assignee.id(), CALLED_AT);
                    return "SUCCESS";
                } catch (TaskLifecycleException exception) {
                    return exception.reason().name();
                }
            };
            Future<String> first = workers.submit(attemptCall);
            Future<String> second = workers.submit(attemptCall);
            Thread.sleep(200);
            lockConnection.commit();

            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertThat(statusInDatabase(taskId)).isEqualTo("IN_PROGRESS");
        assertThat(count("notification_events")).isEqualTo(1);
    }

    private UUID seedTask(String taskStatus, long version) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (id, ticket_number, category, system_name, estimated_minutes,
                    process_number, operation_date, operation_start_at, operation_end_at, creator_id,
                    current_assignee_id, status, auto_assignment_rule, auto_assignment_explanation,
                    version, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), ?, 'VERSION_RELEASE', 'Billing', 60, 'PROC-1',
                    '2026-07-25', ?, ?, UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'DAY_SECOND',
                    'seed', ?, ?, ?)
                """, taskId.toString(), "OPS-" + taskId.toString().replace("-", "").substring(0, 28), Timestamp.from(CALLED_AT),
                Timestamp.from(COMPLETED_AT), creator.id().toString(), assignee.id().toString(), taskStatus,
                version, Timestamp.from(CALLED_AT), Timestamp.from(CALLED_AT));
        return taskId;
    }

    private String statusInDatabase(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = UUID_TO_BIN(?)", String.class, taskId.toString());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private UserAccount account(String username, RoleName role) {
        return users.saveAndFlush(UserAccount.create(username, username, passwords.encode("Task-Test-Password-1"), Set.of(role), false));
    }

    private CurrentUser currentUser(UserAccount account) {
        return new CurrentUser(account.id(), account.username(), account.displayName(), account.roles(), false);
    }

    private UsernamePasswordAuthenticationToken authenticationFor(CurrentUser user) {
        return new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.roles().iterator().next().name())));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean @Primary Clock fixedTaskLifecycleClock() {
            return Clock.fixed(CALLED_AT, ZoneOffset.UTC);
        }
    }
}
