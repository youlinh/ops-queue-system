package com.acme.opsqueue.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.acme.opsqueue.support.MySqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = OpsQueueApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedistributionServiceTest extends MySqlIntegrationTest {
    private static final LocalDate DATE = LocalDate.of(2026, 7, 25);
    private static final Instant NOW = Instant.parse("2026-07-25T04:00:00Z");

    @Autowired private RedistributionService service;
    @Autowired private RedistributionAuditTransaction redistributionAudits;
    @Autowired private AssignmentService assignments;
    @Autowired private UserAccountRepository users;
    @Autowired private DutyRosterRepository rosters;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    private UserAccount creator;
    private UserAccount source;
    private UserAccount third;
    private UserAccount fairOne;
    private UserAccount fairTwo;
    private UserAccount leader;

    @BeforeEach
    void resetFixture() {
        executeAsMigrationUser(
                "DROP TRIGGER IF EXISTS trg_test_redistribution_audit_insert_failure");
        jdbc.update("DELETE FROM redistribution_audit_commands");
        truncateAuditLogs();
        jdbc.update("DELETE FROM unavailability");
        jdbc.update("DELETE FROM assignment_histories");
        jdbc.update("DELETE FROM tasks");
        rosters.deleteAll();
        users.findAll().stream()
                .filter(account -> !account.username().equals("test-bootstrap-leader"))
                .forEach(users::delete);
        creator = account("redistribution-creator", RoleName.DEVELOPER);
        source = account("redistribution-source", RoleName.OPERATOR);
        third = account("redistribution-third", RoleName.OPERATOR);
        fairOne = account("redistribution-fair-one", RoleName.OPERATOR);
        fairTwo = account("redistribution-fair-two", RoleName.OPERATOR);
        leader = account("redistribution-leader", RoleName.LEADER);
        rosters.saveAndFlush(DutyRoster.of(DATE, source.id(), third.id()));
        rosters.saveAndFlush(DutyRoster.of(DATE.plusDays(1), fairOne.id(), fairTwo.id()));
    }

    @Test
    void previewReturnsOnlyMatchingPendingTasksInTicketOrder() {
        UUID second = seedTask("OPS-20260725-0002", "PENDING", source.id(), DATE, "2026-07-25 02:00:00");
        UUID first = seedTask("OPS-20260725-0001", "PENDING", source.id(), DATE, "2026-07-25 01:00:00");
        seedTask("OPS-20260725-0003", "IN_PROGRESS", source.id(), DATE, "2026-07-25 03:00:00");
        seedTask("OPS-20260726-0001", "PENDING", source.id(), DATE.plusDays(1), "2026-07-26 01:00:00");
        seedTask("OPS-20260725-0004", "PENDING", third.id(), DATE, "2026-07-25 04:00:00");

        List<RedistributionTask> preview =
                service.previewRedistribution(source.id(), DATE, leader.id());

        assertThat(preview).extracting(RedistributionTask::taskId)
                .containsExactly(first, second);
    }

    @Test
    void redistributionMovesOnlyPendingTasksRerunsEngineAndWritesHistory() {
        UUID pending = seedTask("OPS-20260725-0001", "PENDING", source.id(), DATE, "2026-07-25 02:00:00");
        UUID inProgress = seedTask("OPS-20260725-0002", "IN_PROGRESS", source.id(), DATE, "2026-07-25 03:00:00");
        assignments.setUnavailable(source.id(), DATE, "无法参与", leader.id(), NOW);

        RedistributionResult result =
                service.redistribute(source.id(), DATE, leader.id(), "今日无法参与", NOW);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().success()).isTrue();
        assertThat(assignee(pending)).isEqualTo(third.id());
        assertThat(assignee(inProgress)).isEqualTo(source.id());
        assertThat(historyCount(pending, "REASSIGN")).isEqualTo(1);
        assertThat(manualAttention(pending)).isFalse();
        assertThat(auditCount("REDISTRIBUTION_EXECUTED")).isEqualTo(1);
        assertThat(pendingAuditCount()).isZero();
    }

    @Test
    void auditWriteFailureKeepsAssignmentsAndLeavesRecoverableCommand() {
        UUID pending = seedTask(
                "OPS-20260725-0001", "PENDING", source.id(), DATE,
                "2026-07-25 02:00:00");
        assignments.setUnavailable(
                source.id(), DATE, "cannot participate", leader.id(), NOW);
        executeAsMigrationUser("""
                CREATE TRIGGER trg_test_redistribution_audit_insert_failure
                BEFORE INSERT ON audit_logs
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected audit failure'
                """);

        RedistributionResult result;
        try {
            result = service.redistribute(
                    source.id(), DATE, leader.id(), "recoverable audit", NOW);
        } finally {
            executeAsMigrationUser(
                    "DROP TRIGGER IF EXISTS trg_test_redistribution_audit_insert_failure");
        }

        assertThat(result.items()).singleElement().satisfies(item ->
                assertThat(item.success()).isTrue());
        assertThat(assignee(pending)).isEqualTo(third.id());
        assertThat(auditCount("REDISTRIBUTION_EXECUTED")).isZero();
        assertThat(pendingAuditCount()).isEqualTo(1);

        UUID commandId = UUID.fromString(jdbc.queryForObject(
                "SELECT BIN_TO_UUID(id) FROM redistribution_audit_commands",
                String.class));
        redistributionAudits.finalizeCommand(commandId);

        assertThat(auditCount("REDISTRIBUTION_EXECUTED")).isEqualTo(1);
        assertThat(pendingAuditCount()).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT JSON_UNQUOTE(JSON_EXTRACT(after_json, '$.successCount'))
                FROM audit_logs
                WHERE action = 'REDISTRIBUTION_EXECUTED'
                """,
                String.class)).isEqualTo("1");
    }

    @Test
    void completedItemsRecoverAsExecutedWhenReadyTransitionInitiallyFails() {
        UUID pending = seedTask(
                "OPS-20260725-0001", "PENDING", source.id(), DATE,
                "2026-07-25 02:00:00");
        assignments.setUnavailable(
                source.id(), DATE, "cannot participate", leader.id(), NOW);
        executeAsMigrationUser("""
                CREATE TRIGGER trg_test_redistribution_ready_failure
                BEFORE UPDATE ON redistribution_audit_commands
                FOR EACH ROW
                BEGIN
                    IF NEW.command_state = 'READY' THEN
                        SIGNAL SQLSTATE '45000'
                            SET MESSAGE_TEXT = 'injected ready transition failure';
                    END IF;
                END
                """);

        RedistributionResult result;
        try {
            result = service.redistribute(
                    source.id(), DATE, leader.id(), "recover ready", NOW);
        } finally {
            executeAsMigrationUser(
                    "DROP TRIGGER IF EXISTS trg_test_redistribution_ready_failure");
        }

        assertThat(result.items()).singleElement().satisfies(item ->
                assertThat(item.success()).isTrue());
        assertThat(assignee(pending)).isEqualTo(third.id());
        assertThat(auditCount("REDISTRIBUTION_EXECUTED")).isZero();
        UUID commandId = UUID.fromString(jdbc.queryForObject(
                "SELECT BIN_TO_UUID(id) FROM redistribution_audit_commands",
                String.class));
        assertThat(jdbc.queryForObject("""
                SELECT processed_count FROM redistribution_audit_commands
                WHERE id = UUID_TO_BIN(?)
                """, Integer.class, commandId.toString())).isEqualTo(1);
        jdbc.update("""
                UPDATE redistribution_audit_commands
                SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 MINUTE)
                WHERE id = UUID_TO_BIN(?)
                """, commandId.toString());

        assertThat(redistributionAudits.recoverExpiredCommand(commandId))
                .isEqualTo(RedistributionAuditTransaction.RecoveryOutcome.READY);
        assertThat(redistributionAudits.finalizeCommand(commandId)).isTrue();
        assertThat(auditCount("REDISTRIBUTION_EXECUTED")).isEqualTo(1);
        assertThat(auditCount("REDISTRIBUTION_INTERRUPTED")).isZero();
    }

    @Test
    void runningCommandCannotBeReportedAsExecutedAndExpiresAsInterrupted() {
        UUID commandId = redistributionAudits.begin(
                leader.id(), source.id(), DATE, 2, "192.0.2.90", NOW);

        assertThat(redistributionAudits.finalizeCommand(commandId)).isFalse();
        assertThat(auditCount("REDISTRIBUTION_EXECUTED")).isZero();
        jdbc.update("""
                UPDATE redistribution_audit_commands
                SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 MINUTE)
                WHERE id = UUID_TO_BIN(?)
                """,
                commandId.toString());

        assertThat(redistributionAudits.recoverExpiredCommand(commandId))
                .isEqualTo(
                        RedistributionAuditTransaction.RecoveryOutcome.INTERRUPTED);
        assertThat(auditCount("REDISTRIBUTION_EXECUTED")).isZero();
        assertThat(auditCount("REDISTRIBUTION_INTERRUPTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT occurred_at > ?
                FROM audit_logs
                WHERE action = 'REDISTRIBUTION_INTERRUPTED'
                """, Boolean.class, Timestamp.from(NOW))).isTrue();
        assertThat(pendingAuditCount()).isZero();
    }

    @Test
    void noCandidateKeepsOriginalAssignmentAndMarksManualAttention() {
        UUID pending = seedTask("OPS-20260725-0001", "PENDING", source.id(), DATE, "2026-07-25 02:00:00");
        for (UserAccount operator : List.of(source, third, fairOne, fairTwo)) {
            assignments.setUnavailable(operator.id(), DATE, "无法参与", leader.id(), NOW);
        }

        RedistributionResult result =
                service.redistribute(source.id(), DATE, leader.id(), "重新分配", NOW);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.success()).isFalse();
            assertThat(item.needsManualAttention()).isTrue();
            assertThat(item.error()).isNotBlank();
        });
        assertThat(assignee(pending)).isEqualTo(source.id());
        assertThat(manualAttention(pending)).isTrue();
        assertThat(historyCount(pending, "REASSIGN")).isZero();
    }

    @Test
    void fairRedistributionExcludesNextDayDutyOperators() {
        UserAccount eligible =
                account("redistribution-eligible", RoleName.OPERATOR);
        UUID pending = seedTask(
                "OPS-20260725-0001", "PENDING", source.id(), DATE,
                "2026-07-25 11:00:00");
        assignments.setUnavailable(
                source.id(), DATE, "无法参与", leader.id(), NOW);
        assignments.setUnavailable(
                third.id(), DATE, "无法参与", leader.id(), NOW);

        RedistributionResult result =
                service.redistribute(
                        source.id(), DATE, leader.id(), "重新分配", NOW);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.success()).isTrue();
            assertThat(item.assigneeId()).isEqualTo(eligible.id());
        });
        assertThat(assignee(pending)).isEqualTo(eligible.id());
    }

    @Test
    void oneFailedItemDoesNotRollbackLaterSuccessfulItem() {
        jdbc.update("DELETE FROM duty_rosters WHERE duty_date = ?", DATE.plusDays(1));
        assignments.setUnavailable(source.id(), DATE, "无法参与", leader.id(), NOW);
        seedTask("OPS-20260725-0000", "PENDING", third.id(), DATE, "2026-07-25 10:00:00");
        seedTask("OPS-20260725-000A", "PENDING", third.id(), DATE, "2026-07-25 10:10:00");
        seedTask("OPS-20260725-000B", "PENDING", third.id(), DATE, "2026-07-25 10:20:00");
        UUID failing = seedTask("OPS-20260725-0001", "PENDING", source.id(), DATE, "2026-07-25 11:00:00");
        UUID succeeding = seedTask("OPS-20260725-0002", "PENDING", source.id(), DATE, "2026-07-25 02:00:00");

        RedistributionResult result =
                service.redistribute(source.id(), DATE, leader.id(), "批量重分配", NOW);

        assertThat(result.items()).extracting(RedistributionItemResult::success)
                .containsExactly(false, true);
        assertThat(assignee(failing)).isEqualTo(source.id());
        assertThat(manualAttention(failing)).isTrue();
        assertThat(assignee(succeeding)).isEqualTo(third.id());
        assertThat(manualAttention(succeeding)).isFalse();
    }

    @Test
    void redistributionEndpointsAreLeaderOnlyRequireCsrfAndReturnStablePayload() throws Exception {
        UUID pending = seedTask("OPS-20260725-0001", "PENDING", source.id(), DATE, "2026-07-25 02:00:00");
        assignments.setUnavailable(source.id(), DATE, "无法参与", leader.id(), NOW);

        mvc.perform(get("/api/assignments/redistribution/preview")
                        .param("operatorId", source.id().toString())
                        .param("date", DATE.toString())
                        .with(authentication(auth(source))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/assignments/redistribution/preview")
                        .param("operatorId", source.id().toString())
                        .param("date", DATE.toString())
                        .with(authentication(auth(leader))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value(pending.toString()));

        String body = """
                {"operatorId":"%s","date":"%s","reason":"今日无法参与"}
                """.formatted(source.id(), DATE);
        mvc.perform(post("/api/assignments/redistribution")
                        .with(authentication(auth(leader)))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/assignments/redistribution")
                        .with(csrf()).with(authentication(auth(source)))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/assignments/redistribution")
                        .with(csrf()).with(authentication(auth(leader)))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].taskId").value(pending.toString()))
                .andExpect(jsonPath("$.items[0].success").value(true));
        assertThat(auditCount("REDISTRIBUTION_EXECUTED")).isEqualTo(1);
    }

    private UUID seedTask(
            String ticketNumber,
            String status,
            UUID assigneeId,
            LocalDate date,
            String operationStart) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (id, ticket_number, category, system_name, estimated_minutes,
                    process_number, operation_date, operation_start_at, operation_end_at, creator_id,
                    current_assignee_id, status, auto_assignment_rule, auto_assignment_explanation,
                    version, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), ?, 'DATA_MAINTENANCE', 'Core', 60, 'PROC-REDIST',
                    ?, ?, DATE_ADD(?, INTERVAL 1 HOUR), UUID_TO_BIN(?), UUID_TO_BIN(?), ?,
                    'DAY_SECOND', 'seed', 0, ?, ?)
                """, taskId.toString(), ticketNumber, date, operationStart, operationStart,
                creator.id().toString(), assigneeId.toString(), status,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return taskId;
    }

    private UUID assignee(UUID taskId) {
        return UUID.fromString(jdbc.queryForObject("""
                SELECT BIN_TO_UUID(current_assignee_id) FROM tasks WHERE id = UUID_TO_BIN(?)
                """, String.class, taskId.toString()));
    }

    private boolean manualAttention(UUID taskId) {
        return jdbc.queryForObject("""
                SELECT needs_manual_attention FROM tasks WHERE id = UUID_TO_BIN(?)
                """, Boolean.class, taskId.toString());
    }

    private int historyCount(UUID taskId, String type) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM assignment_histories
                WHERE task_id = UUID_TO_BIN(?) AND assignment_type = ?
                """, Integer.class, taskId.toString(), type);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ?",
                Integer.class,
                action);
    }

    private int pendingAuditCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM redistribution_audit_commands",
                Integer.class);
    }

    private UserAccount account(String username, RoleName role) {
        return users.saveAndFlush(UserAccount.create(
                username, username, passwords.encode("Redistribution-Test-Password-1"),
                Set.of(role), false));
    }

    private UsernamePasswordAuthenticationToken auth(UserAccount account) {
        CurrentUser principal = new CurrentUser(
                account.id(), account.username(), account.displayName(), account.roles(), false);
        return new UsernamePasswordAuthenticationToken(principal, null,
                account.roles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                        .toList());
    }
}
