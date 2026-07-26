package com.acme.opsqueue.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
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
import com.acme.opsqueue.roster.DutyRoster;
import com.acme.opsqueue.roster.DutyRosterRepository;
import com.acme.opsqueue.scheduling.AssignmentRule;
import com.acme.opsqueue.support.MySqlIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = OpsQueueApplication.class,
        properties = "task.creation.integration-test-context=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CreateTaskIntegrationTest.FixedClockConfiguration.class)
class CreateTaskIntegrationTest extends MySqlIntegrationTest {
    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-25T01:00:00Z");
    private static final Instant DAY_START = Instant.parse("2026-07-25T02:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-07-25T03:00:00Z");
    private static final LocalDate OPERATION_DATE = LocalDate.of(2026, 7, 25);

    @Autowired
    private CreateTaskService service;

    @Autowired
    private DutyRosterRepository rosters;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    private UserAccount creator;
    private UserAccount second;
    private UserAccount third;
    private UserAccount alpha;

    @BeforeEach
    void resetFixture() {
        truncateAuditLogs();
        jdbc.update("DELETE FROM assignment_histories");
        jdbc.update("DELETE FROM tasks");
        jdbc.update("DELETE FROM unavailability");
        jdbc.update("DELETE FROM daily_ticket_sequences");
        jdbc.update("DELETE FROM schedule_date_locks");
        rosters.deleteAll();
        users.findAll().stream()
                .filter(account -> !account.username().equals("test-bootstrap-leader"))
                .forEach(users::delete);

        creator = account("task-developer", Set.of(RoleName.DEVELOPER));
        second = account("task-second", Set.of(RoleName.OPERATOR));
        third = account("task-third", Set.of(RoleName.OPERATOR));
        alpha = account("task-alpha", Set.of(RoleName.OPERATOR));
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(TaskCategory.values()).flatMap(category -> Stream.of(
                arguments(category, " ", 30, "PROC-1", DAY_START, DAY_END),
                arguments(category, "Billing", 0, "PROC-1", DAY_START, DAY_END),
                arguments(category, "Billing", 30, " ", DAY_START, DAY_END),
                arguments(category, "Billing", 30, "PROC-1", DAY_START, DAY_START)));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidFieldsForBothCategoriesWithoutSideEffects(
            TaskCategory category,
            String systemName,
            int estimatedMinutes,
            String processNumber,
            Instant start,
            Instant end) {
        CreateTaskCommand command = new CreateTaskCommand(
                category, systemName, estimatedMinutes, processNumber, start, end);

        assertThatThrownBy(() -> service.create(command, creator.id(), SUBMITTED_AT))
                .isInstanceOf(InvalidTaskRequestException.class);
        assertNoAllocationSideEffects();
    }

    @Test
    void missingOperationDateRosterRejectsBeforeAllocatingTicketOrHistory() {
        assertThatThrownBy(() -> service.create(validCommand(), creator.id(), SUBMITTED_AT))
                .isInstanceOf(MissingDutyRosterException.class)
                .hasMessageContaining("2026-07-25");

        assertNoAllocationSideEffects();
    }

    @Test
    void directDaytimeBranchDoesNotRequireTomorrowRosterAndPersistsDecisionEvidence() {
        rosters.saveAndFlush(DutyRoster.of(OPERATION_DATE, second.id(), third.id()));

        CreatedTask result = service.create(
                new CreateTaskCommand(
                        TaskCategory.DATA_MAINTENANCE,
                        "  Billing Core  ",
                        45,
                        "  PROC-7788  ",
                        DAY_START,
                        DAY_END),
                creator.id(),
                SUBMITTED_AT);

        assertThat(result.ticketNumber()).isEqualTo("OPS-20260725-0001");
        assertThat(result.assigneeId()).isEqualTo(second.id());
        assertThat(result.assignmentRule()).isEqualTo(AssignmentRule.DAY_SECOND);
        assertThat(jdbc.queryForMap("""
                        SELECT ticket_number, category, system_name, estimated_minutes,
                               process_number, operation_date, creator_id,
                               current_assignee_id, status, auto_assignment_rule, version
                        FROM tasks WHERE id = UUID_TO_BIN(?)
                        """, result.id().toString()))
                .containsEntry("ticket_number", "OPS-20260725-0001")
                .containsEntry("category", "DATA_MAINTENANCE")
                .containsEntry("system_name", "Billing Core")
                .containsEntry("estimated_minutes", 45)
                .containsEntry("process_number", "PROC-7788")
                .containsEntry("operation_date", java.sql.Date.valueOf(OPERATION_DATE))
                .containsEntry("status", "PENDING")
                .containsEntry("auto_assignment_rule", "DAY_SECOND")
                .containsEntry("version", 0L);
        assertThat(jdbc.queryForObject(
                "SELECT BIN_TO_UUID(creator_id) FROM tasks WHERE id = UUID_TO_BIN(?)",
                String.class,
                result.id().toString())).isEqualToIgnoringCase(creator.id().toString());
        assertThat(jdbc.queryForObject(
                "SELECT BIN_TO_UUID(current_assignee_id) FROM tasks WHERE id = UUID_TO_BIN(?)",
                String.class,
                result.id().toString())).isEqualToIgnoringCase(second.id().toString());
        assertThat(jdbc.queryForMap("""
                        SELECT DATE_FORMAT(operation_start_at, '%Y-%m-%dT%H:%i:%s.%fZ') operation_start,
                               DATE_FORMAT(operation_end_at, '%Y-%m-%dT%H:%i:%s.%fZ') operation_end
                        FROM tasks WHERE id = UUID_TO_BIN(?)
                        """, result.id().toString()))
                .containsEntry("operation_start", "2026-07-25T02:00:00.000000Z")
                .containsEntry("operation_end", "2026-07-25T03:00:00.000000Z");

        var history = jdbc.queryForMap("""
                SELECT assignment_type, BIN_TO_UUID(old_assignee_id) old_assignee,
                       BIN_TO_UUID(new_assignee_id) new_assignee, assignment_rule,
                       reason, candidate_snapshot, BIN_TO_UUID(actor_id) actor
                FROM assignment_histories WHERE task_id = UUID_TO_BIN(?)
                """, result.id().toString());
        assertThat(history)
                .containsEntry("assignment_type", "AUTO")
                .containsEntry("assignment_rule", "DAY_SECOND");
        assertThat((String) history.get("new_assignee"))
                .isEqualToIgnoringCase(second.id().toString());
        assertThat(history.get("old_assignee")).isNull();
        assertThat((String) history.get("actor"))
                .isEqualToIgnoringCase(creator.id().toString());
        assertThat((String) history.get("reason")).contains("08:30");
        assertThat((String) history.get("candidate_snapshot"))
                .contains(second.id().toString(), third.id().toString(), "dailyTaskCount");
    }

    @Test
    void fairAllocationRequiresTomorrowRosterAndRollsBackEverythingWhenItIsMissing() {
        rosters.saveAndFlush(DutyRoster.of(OPERATION_DATE, second.id(), third.id()));
        markUnavailable(second);
        markUnavailable(third);

        assertThatThrownBy(() -> service.create(
                        afterHoursCommand(), creator.id(), SUBMITTED_AT))
                .isInstanceOf(MissingNextDayDutyRosterException.class)
                .hasMessageContaining("2026-07-26");

        assertNoAllocationSideEffects();
    }

    @Test
    void allUnavailableFailureIsAtomic() {
        rosters.saveAndFlush(DutyRoster.of(OPERATION_DATE, second.id(), third.id()));
        rosters.saveAndFlush(DutyRoster.of(
                OPERATION_DATE.plusDays(1), second.id(), third.id()));
        markUnavailable(second);
        markUnavailable(third);
        markUnavailable(alpha);

        assertThatThrownBy(() -> service.create(
                        afterHoursCommand(), creator.id(), SUBMITTED_AT))
                .isInstanceOf(NoTaskAssigneeException.class);

        assertNoAllocationSideEffects();
    }

    @Test
    void fairAllocationReloadsMetricsAndExcludesTomorrowDutyBeforePersisting() {
        UserAccount bravo = account("task-bravo", Set.of(RoleName.OPERATOR));
        UserAccount charlie = account("task-charlie", Set.of(RoleName.OPERATOR));
        UserAccount delta = account("task-delta", Set.of(RoleName.OPERATOR));
        rosters.saveAndFlush(DutyRoster.of(OPERATION_DATE, second.id(), third.id()));
        rosters.saveAndFlush(DutyRoster.of(
                OPERATION_DATE.plusDays(1), alpha.id(), bravo.id()));
        markUnavailable(second);
        markUnavailable(third);
        markUnavailable(delta);

        seedCompletedTask("FAIR-ALPHA", alpha, 10, "2026-07-25T00:10:00Z");
        seedCompletedTask("FAIR-BRAVO-1", bravo, 20, "2026-07-25T00:20:00Z");
        seedCompletedTask("FAIR-BRAVO-2", bravo, 20, "2026-07-25T00:30:00Z");
        seedCompletedTask("FAIR-CHARLIE", charlie, 60, "2026-07-24T23:00:00Z");

        CreatedTask result = service.create(afterHoursCommand(), creator.id(), SUBMITTED_AT);

        assertThat(result.assignmentRule()).isEqualTo(AssignmentRule.FAIR);
        assertThat(result.assigneeId()).isEqualTo(charlie.id());
        assertThat(jdbc.queryForObject(
                "SELECT BIN_TO_UUID(current_assignee_id) FROM tasks WHERE id = UUID_TO_BIN(?)",
                String.class,
                result.id().toString())).isEqualToIgnoringCase(charlie.id().toString());
        assertThat(jdbc.queryForObject(
                "SELECT assignment_rule FROM assignment_histories WHERE task_id = UUID_TO_BIN(?)",
                String.class,
                result.id().toString())).isEqualTo("FAIR");
        assertThat(jdbc.queryForObject(
                "SELECT candidate_snapshot FROM assignment_histories WHERE task_id = UUID_TO_BIN(?)",
                String.class,
                result.id().toString()))
                .contains(
                        charlie.id().toString(),
                        "\"dailyTaskCount\": 1",
                        "\"monthlyActualMinutes\": 60",
                        "\"lastAssignedAt\": \"2026-07-24T23:00:00Z\"")
                .doesNotContain(alpha.id().toString(), bravo.id().toString());
    }

    @Test
    void twentyConcurrentCreatesAllocateAnExactGapFreeTicketRange() throws Exception {
        rosters.saveAndFlush(DutyRoster.of(OPERATION_DATE, second.id(), third.id()));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CreatedTask>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 20; index++) {
                int request = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.create(
                            new CreateTaskCommand(
                                    TaskCategory.VERSION_RELEASE,
                                    "Concurrent " + request,
                                    15,
                                    "PROC-" + request,
                                    DAY_START,
                                    DAY_END),
                            creator.id(),
                            SUBMITTED_AT);
                }));
            }
            start.countDown();

            List<CreatedTask> results = new ArrayList<>();
            for (Future<CreatedTask> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(results)
                    .extracting(CreatedTask::ticketNumber)
                    .containsExactlyInAnyOrderElementsOf(
                            Stream.iterate(1, number -> number + 1)
                                    .limit(20)
                                    .map(number -> "OPS-20260725-%04d".formatted(number))
                                    .toList());
            assertThat(results).extracting(CreatedTask::id).doesNotHaveDuplicates();
        }

        assertThat(count("tasks")).isEqualTo(20);
        assertThat(count("assignment_histories")).isEqualTo(20);
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence FROM daily_ticket_sequences WHERE issue_date = ?",
                Integer.class,
                LocalDate.of(2026, 7, 25))).isEqualTo(20);
    }

    @Test
    void endpointIsDeveloperOnlyAndUsesPrincipalInsteadOfSpoofedCreator() throws Exception {
        rosters.saveAndFlush(DutyRoster.of(OPERATION_DATE, second.id(), third.id()));
        CurrentUser operatorPrincipal = currentUser(second, RoleName.OPERATOR);

        mvc.perform(post("/api/tasks")
                        .with(csrf())
                        .with(authentication(authenticationFor(operatorPrincipal)))
                        .contentType(APPLICATION_JSON)
                        .content(requestJson(alpha.id())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertNoAllocationSideEffects();

        CurrentUser developerPrincipal = currentUser(creator, RoleName.DEVELOPER);
        mvc.perform(post("/api/tasks")
                        .with(csrf())
                        .with(authentication(authenticationFor(developerPrincipal)))
                        .contentType(APPLICATION_JSON)
                        .content(requestJson(alpha.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketNumber").value("OPS-20260725-0001"))
                .andExpect(jsonPath("$.assigneeId").value(second.id().toString()))
                .andExpect(jsonPath("$.assignmentRule").value("DAY_SECOND"));

        assertThat(jdbc.queryForObject(
                "SELECT BIN_TO_UUID(creator_id) FROM tasks",
                String.class)).isEqualToIgnoringCase(creator.id().toString());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM audit_logs
                WHERE action = 'TASK_CREATED'
                  AND object_id = (SELECT id FROM tasks LIMIT 1)
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void auditInsertFailureRollsBackTaskTicketAndAssignment() throws Exception {
        rosters.saveAndFlush(DutyRoster.of(OPERATION_DATE, second.id(), third.id()));
        CurrentUser developerPrincipal = currentUser(creator, RoleName.DEVELOPER);
        executeAsMigrationUser("""
                CREATE TRIGGER trg_test_audit_insert_failure
                BEFORE INSERT ON audit_logs
                FOR EACH ROW
                SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'injected audit insert failure'
                """);
        try {
            mvc.perform(post("/api/tasks")
                            .with(csrf())
                            .with(authentication(authenticationFor(developerPrincipal)))
                            .contentType(APPLICATION_JSON)
                            .content(requestJson(alpha.id())))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            executeAsMigrationUser(
                    "DROP TRIGGER IF EXISTS trg_test_audit_insert_failure");
        }

        assertNoAllocationSideEffects();
    }

    @Test
    void forcedPasswordChangeReturnsStableJsonWithoutCreatingTask() throws Exception {
        rosters.saveAndFlush(DutyRoster.of(OPERATION_DATE, second.id(), third.id()));
        CurrentUser forcedChangePrincipal = new CurrentUser(
                creator.id(),
                creator.username(),
                creator.displayName(),
                Set.of(RoleName.DEVELOPER),
                true);

        mvc.perform(post("/api/tasks")
                        .with(csrf())
                        .with(authentication(authenticationFor(forcedChangePrincipal)))
                        .contentType(APPLICATION_JSON)
                        .content(requestJson(alpha.id())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Password change is required"));

        assertNoAllocationSideEffects();
    }

    @Test
    void disabledDutyUserInRosterReturnsStableConflictAndRollsBack() throws Exception {
        rosters.saveAndFlush(DutyRoster.of(OPERATION_DATE, second.id(), third.id()));
        second.disable();
        users.saveAndFlush(second);
        CurrentUser developerPrincipal = currentUser(creator, RoleName.DEVELOPER);

        mvc.perform(post("/api/tasks")
                        .with(csrf())
                        .with(authentication(authenticationFor(developerPrincipal)))
                        .contentType(APPLICATION_JSON)
                        .content(requestJson(alpha.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUTY_ROSTER_STALE"));

        assertNoAllocationSideEffects();
    }

    @Test
    void malformedCategoryReturnsTheStableValidationErrorBody() throws Exception {
        CurrentUser developerPrincipal = currentUser(creator, RoleName.DEVELOPER);

        mvc.perform(post("/api/tasks")
                        .with(csrf())
                        .with(authentication(authenticationFor(developerPrincipal)))
                        .contentType(APPLICATION_JSON)
                        .content(requestJson(alpha.id())
                                .replace("VERSION_RELEASE", "NOT_A_CATEGORY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TASK_REQUEST"));

        assertNoAllocationSideEffects();
    }

    private UserAccount account(String username, Set<RoleName> roles) {
        return users.saveAndFlush(UserAccount.create(
                username,
                username,
                passwords.encode("Task-Test-Password-1"),
                roles,
                false));
    }

    private CurrentUser currentUser(UserAccount account, RoleName role) {
        return new CurrentUser(
                account.id(), account.username(), account.displayName(), Set.of(role), false);
    }

    private UsernamePasswordAuthenticationToken authenticationFor(CurrentUser user) {
        return new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.roles().iterator().next().name())));
    }

    private CreateTaskCommand validCommand() {
        return new CreateTaskCommand(
                TaskCategory.VERSION_RELEASE,
                "Billing",
                30,
                "PROC-1",
                DAY_START,
                DAY_END);
    }

    private CreateTaskCommand afterHoursCommand() {
        return new CreateTaskCommand(
                TaskCategory.VERSION_RELEASE,
                "Billing",
                30,
                "PROC-2",
                Instant.parse("2026-07-25T12:00:00Z"),
                Instant.parse("2026-07-25T13:00:00Z"));
    }

    private void markUnavailable(UserAccount account) {
        jdbc.update("""
                INSERT INTO unavailability
                    (user_id, unavailable_date, reason, created_by_user_id, created_at)
                VALUES (UUID_TO_BIN(?), ?, 'leave', UUID_TO_BIN(?), ?)
                """,
                account.id().toString(),
                OPERATION_DATE,
                creator.id().toString(),
                java.sql.Timestamp.from(SUBMITTED_AT));
    }

    private void seedCompletedTask(
            String ticketNumber,
            UserAccount assignee,
            int actualMinutes,
            String assignedAt) {
        UUID taskId = UUID.randomUUID();
        Instant actualStart = Instant.parse("2026-07-25T04:00:00Z");
        Instant actualEnd = actualStart.plusSeconds(actualMinutes * 60L);
        jdbc.update("""
                INSERT INTO tasks (
                    id, ticket_number, category, system_name, estimated_minutes,
                    process_number, operation_date, operation_start_at, operation_end_at,
                    creator_id, current_assignee_id, status, auto_assignment_rule,
                    auto_assignment_explanation, version, actual_start_at, actual_end_at,
                    created_at, updated_at)
                VALUES (
                    UUID_TO_BIN(?), ?, 'DATA_MAINTENANCE', 'Fair metric seed', 30,
                    ?, ?, ?, ?, UUID_TO_BIN(?), UUID_TO_BIN(?), 'COMPLETED', 'FAIR',
                    'Seed task for fair-allocation metric coverage', 0, ?, ?, ?, ?)
                """,
                taskId.toString(),
                ticketNumber,
                ticketNumber,
                OPERATION_DATE,
                java.sql.Timestamp.from(DAY_START),
                java.sql.Timestamp.from(DAY_END),
                creator.id().toString(),
                assignee.id().toString(),
                java.sql.Timestamp.from(actualStart),
                java.sql.Timestamp.from(actualEnd),
                java.sql.Timestamp.from(SUBMITTED_AT),
                java.sql.Timestamp.from(SUBMITTED_AT));
        jdbc.update("""
                INSERT INTO assignment_histories (
                    id, task_id, assignment_type, old_assignee_id, new_assignee_id,
                    assignment_rule, reason, candidate_snapshot, actor_id, assigned_at)
                VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), 'AUTO', NULL, UUID_TO_BIN(?), 'FAIR',
                    'Seed assignment timestamp for fair-allocation metric coverage',
                    CAST('[]' AS JSON), UUID_TO_BIN(?), ?)
                """,
                UUID.randomUUID().toString(),
                taskId.toString(),
                assignee.id().toString(),
                creator.id().toString(),
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(
                        Instant.parse(assignedAt), ZoneOffset.UTC)));
    }

    private void assertNoAllocationSideEffects() {
        assertThat(count("tasks")).isZero();
        assertThat(count("assignment_histories")).isZero();
        assertThat(count("daily_ticket_sequences")).isZero();
        assertThat(count("schedule_date_locks")).isZero();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String requestJson(UUID spoofedCreator) {
        return """
                {
                  "category": "VERSION_RELEASE",
                  "systemName": "Billing",
                  "estimatedMinutes": 30,
                  "processNumber": "PROC-API",
                  "operationStart": "2026-07-25T02:00:00Z",
                  "operationEnd": "2026-07-25T03:00:00Z",
                  "creatorId": "%s"
                }
                """.formatted(spoofedCreator);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedTaskCreationClock() {
            return Clock.fixed(SUBMITTED_AT, ZoneOffset.UTC);
        }
    }
}
