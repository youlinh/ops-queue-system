package com.acme.opsqueue.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = OpsQueueApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AssignmentServiceTest.FixedClockConfiguration.class)
class AssignmentServiceTest extends MySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-25T04:00:00Z");
    private static final LocalDate OPERATION_DATE = LocalDate.of(2026, 7, 25);

    @Autowired private AssignmentService service;
    @Autowired private UserAccountRepository users;
    @Autowired private DutyRosterRepository rosters;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    private UserAccount creator;
    private UserAccount assignee;
    private UserAccount target;
    private UserAccount otherOperator;
    private UserAccount leader;
    private UserAccount developer;

    @BeforeEach
    void resetFixture() {
        jdbc.update("DELETE FROM unavailability");
        jdbc.update("DELETE FROM assignment_histories");
        jdbc.update("DELETE FROM tasks");
        rosters.deleteAll();
        users.findAll().stream()
                .filter(account -> !account.username().equals("test-bootstrap-leader"))
                .forEach(users::delete);
        creator = account("assignment-creator", RoleName.DEVELOPER);
        assignee = account("assignment-assignee", RoleName.OPERATOR);
        target = account("assignment-target", RoleName.OPERATOR);
        otherOperator = account("assignment-other", RoleName.OPERATOR);
        leader = account("assignment-leader", RoleName.LEADER);
        developer = account("assignment-developer", RoleName.DEVELOPER);
    }

    @Test
    void currentAssigneeTransfersImmediatelyAndWritesImmutableHistory() {
        UUID taskId = seedTask("PENDING", assignee.id());
        jdbc.update("""
                UPDATE tasks SET needs_manual_attention = TRUE
                WHERE id = UUID_TO_BIN(?)
                """, taskId.toString());

        AssignmentResult result =
                service.transfer(taskId, assignee.id(), target.id(), "临时冲突", NOW);

        assertThat(result.previousAssigneeId()).isEqualTo(assignee.id());
        assertThat(result.assigneeId()).isEqualTo(target.id());
        assertThat(result.warnings()).isEmpty();
        assertThat(task(taskId))
                .containsEntry("assignee", target.id().toString())
                .containsEntry("version", 1L)
                .containsEntry("manual_attention", false);
        assertThat(history(taskId))
                .containsEntry("assignment_type", "TRANSFER")
                .containsEntry("old_assignee", assignee.id().toString())
                .containsEntry("new_assignee", target.id().toString())
                .containsEntry("actor", assignee.id().toString())
                .containsEntry("reason", "临时冲突");
    }

    @Test
    void transferEnforcesOwnershipReasonAndMutableStatus() {
        UUID pending = seedTask("PENDING", assignee.id());
        assertReason(() -> service.transfer(pending, target.id(), otherOperator.id(), "转交", NOW),
                AssignmentValidationException.Reason.FORBIDDEN);
        assertReason(() -> service.transfer(pending, assignee.id(), target.id(), "  ", NOW),
                AssignmentValidationException.Reason.INVALID_REQUEST);

        UUID completed = seedTask("COMPLETED", assignee.id());
        assertReason(() -> service.transfer(completed, assignee.id(), target.id(), "转交", NOW),
                AssignmentValidationException.Reason.CONFLICT);
    }

    @Test
    void transferRejectsUnavailableNonOperatorDisabledAndSameTargets() {
        UUID first = seedTask("PENDING", assignee.id());
        service.setUnavailable(target.id(), OPERATION_DATE, "请假", leader.id(), NOW);
        assertReason(() -> service.transfer(first, assignee.id(), target.id(), "转交", NOW),
                AssignmentValidationException.Reason.INVALID_TARGET);

        assertReason(() -> service.transfer(first, assignee.id(), developer.id(), "转交", NOW),
                AssignmentValidationException.Reason.INVALID_TARGET);
        otherOperator.disable();
        users.saveAndFlush(otherOperator);
        assertReason(() -> service.transfer(first, assignee.id(), otherOperator.id(), "转交", NOW),
                AssignmentValidationException.Reason.INVALID_TARGET);
        assertReason(() -> service.transfer(first, assignee.id(), assignee.id(), "转交", NOW),
                AssignmentValidationException.Reason.INVALID_TARGET);
    }

    @Test
    void transferAllowsNextDayDutyTargetAndReturnsWarning() {
        rosters.saveAndFlush(DutyRoster.of(OPERATION_DATE.plusDays(1), target.id(), otherOperator.id()));
        UUID taskId = seedTask("PENDING", assignee.id());

        AssignmentResult result =
                service.transfer(taskId, assignee.id(), target.id(), "紧急转交", NOW);

        assertThat(result.assigneeId()).isEqualTo(target.id());
        assertThat(result.warnings()).containsExactly("目标人员是次日值班人员");
    }

    @Test
    void enabledLeaderAdjustsAnotherAssigneesInProgressTaskButOperatorCannot() {
        UUID taskId = seedTask("IN_PROGRESS", assignee.id());

        assertReason(() -> service.leaderAdjust(
                        taskId, otherOperator.id(), target.id(), "人工调整", NOW),
                AssignmentValidationException.Reason.FORBIDDEN);

        AssignmentResult result =
                service.leaderAdjust(taskId, leader.id(), target.id(), "人工调整", NOW);

        assertThat(result.assigneeId()).isEqualTo(target.id());
        assertThat(history(taskId))
                .containsEntry("assignment_type", "REASSIGN")
                .containsEntry("actor", leader.id().toString());
    }

    @Test
    void leaderCreatesUpdatesAndRemovesUnavailabilityWhileNonLeaderIsForbidden() {
        assertReason(() -> service.setUnavailable(
                        target.id(), OPERATION_DATE, "请假", otherOperator.id(), NOW),
                AssignmentValidationException.Reason.FORBIDDEN);

        UnavailabilityView created =
                service.setUnavailable(target.id(), OPERATION_DATE, "请假", leader.id(), NOW);
        assertThat(created.reason()).isEqualTo("请假");
        service.setUnavailable(target.id(), OPERATION_DATE, "培训", leader.id(), NOW);
        assertThat(jdbc.queryForObject("""
                SELECT reason FROM unavailability
                WHERE user_id = UUID_TO_BIN(?) AND unavailable_date = ?
                """, String.class, target.id().toString(), OPERATION_DATE)).isEqualTo("培训");

        service.removeUnavailable(target.id(), OPERATION_DATE, leader.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM unavailability", Integer.class)).isZero();
    }

    @Test
    void removingUnavailabilityRejectsUnknownNonOperatorAndDisabledTargets() throws Exception {
        assertReason(() -> service.removeUnavailable(
                        UUID.randomUUID(), OPERATION_DATE, leader.id()),
                AssignmentValidationException.Reason.INVALID_TARGET);
        assertReason(() -> service.removeUnavailable(
                        developer.id(), OPERATION_DATE, leader.id()),
                AssignmentValidationException.Reason.INVALID_TARGET);

        service.setUnavailable(
                target.id(), OPERATION_DATE, "请假", leader.id(), NOW);
        target.disable();
        users.saveAndFlush(target);
        assertReason(() -> service.removeUnavailable(
                        target.id(), OPERATION_DATE, leader.id()),
                AssignmentValidationException.Reason.INVALID_TARGET);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM unavailability
                WHERE user_id = UUID_TO_BIN(?) AND unavailable_date = ?
                """, Integer.class, target.id().toString(), OPERATION_DATE))
                .isEqualTo(1);

        mvc.perform(delete("/api/unavailability/{operatorId}/{date}",
                        UUID.randomUUID(), OPERATION_DATE)
                        .with(csrf()).with(authentication(auth(leader))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_ASSIGNMENT_TARGET"));
    }

    @Test
    void assignmentEndpointsEnforceAuthenticationCsrfRolesAndAuthenticatedActor() throws Exception {
        UUID taskId = seedTask("PENDING", assignee.id());
        String transfer = """
                {"targetId":"%s","reason":"临时冲突","actorId":"%s"}
                """.formatted(target.id(), leader.id());

        mvc.perform(post("/api/assignments/tasks/{id}/transfer", taskId)
                        .with(csrf())
                        .contentType(APPLICATION_JSON).content(transfer))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assignments/tasks/{id}/transfer", taskId)
                        .with(authentication(auth(assignee)))
                        .contentType(APPLICATION_JSON).content(transfer))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/assignments/tasks/{id}/transfer", taskId)
                        .with(csrf()).with(authentication(auth(assignee)))
                        .contentType(APPLICATION_JSON).content(transfer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(target.id().toString()));

        mvc.perform(post("/api/unavailability")
                        .with(csrf()).with(authentication(auth(otherOperator)))
                        .contentType(APPLICATION_JSON)
                        .content(unavailabilityJson(target.id(), "请假")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/unavailability")
                        .with(csrf()).with(authentication(auth(leader)))
                        .contentType(APPLICATION_JSON)
                        .content(unavailabilityJson(otherOperator.id(), "请假")))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/unavailability/{operatorId}/{date}",
                        otherOperator.id(), OPERATION_DATE)
                        .with(csrf()).with(authentication(auth(leader))))
                .andExpect(status().isNoContent());

        UUID inProgress = seedTask("IN_PROGRESS", assignee.id());
        String adjustment = """
                {"targetId":"%s","reason":"组长调整"}
                """.formatted(otherOperator.id());
        mvc.perform(post("/api/assignments/tasks/{id}/adjust", inProgress)
                        .with(csrf()).with(authentication(auth(assignee)))
                        .contentType(APPLICATION_JSON).content(adjustment))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/assignments/tasks/{id}/adjust", inProgress)
                        .with(authentication(auth(leader)))
                        .contentType(APPLICATION_JSON).content(adjustment))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/assignments/tasks/{id}/adjust", inProgress)
                        .with(csrf()).with(authentication(auth(leader)))
                        .contentType(APPLICATION_JSON).content(adjustment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId")
                        .value(otherOperator.id().toString()));
    }

    private void assertReason(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            AssignmentValidationException.Reason reason) {
        assertThatThrownBy(operation)
                .isInstanceOf(AssignmentValidationException.class)
                .satisfies(error -> assertThat(((AssignmentValidationException) error).reason())
                        .isEqualTo(reason));
    }

    private UUID seedTask(String status, UUID currentAssigneeId) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (id, ticket_number, category, system_name, estimated_minutes,
                    process_number, operation_date, operation_start_at, operation_end_at, creator_id,
                    current_assignee_id, status, auto_assignment_rule, auto_assignment_explanation,
                    version, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), ?, 'VERSION_RELEASE', 'Billing', 60, 'PROC-1',
                    ?, '2026-07-25 10:00:00', '2026-07-25 11:00:00',
                    UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'DAY_SECOND', 'seed', 0, ?, ?)
                """, taskId.toString(), ticket(taskId), OPERATION_DATE, creator.id().toString(),
                currentAssigneeId.toString(), status, Timestamp.from(NOW), Timestamp.from(NOW));
        return taskId;
    }

    private java.util.Map<String, Object> task(UUID taskId) {
        return jdbc.queryForMap("""
                SELECT BIN_TO_UUID(current_assignee_id) assignee, version,
                       needs_manual_attention manual_attention
                FROM tasks WHERE id = UUID_TO_BIN(?)
                """, taskId.toString());
    }

    private java.util.Map<String, Object> history(UUID taskId) {
        return jdbc.queryForMap("""
                SELECT assignment_type, BIN_TO_UUID(old_assignee_id) old_assignee,
                       BIN_TO_UUID(new_assignee_id) new_assignee,
                       BIN_TO_UUID(actor_id) actor, reason
                FROM assignment_histories WHERE task_id = UUID_TO_BIN(?)
                ORDER BY assigned_at DESC LIMIT 1
                """, taskId.toString());
    }

    private UserAccount account(String username, RoleName role) {
        return users.saveAndFlush(UserAccount.create(
                username, username, passwords.encode("Assignment-Test-Password-1"),
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

    private String unavailabilityJson(UUID operatorId, String reason) {
        return """
                {"operatorId":"%s","date":"%s","reason":"%s"}
                """.formatted(operatorId, OPERATION_DATE, reason);
    }

    private static String ticket(UUID id) {
        return "OPS-" + id.toString().replace("-", "").substring(0, 28);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedAssignmentClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
