package com.acme.opsqueue.task;

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
import com.acme.opsqueue.support.MySqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
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
class TaskQueryApiTest extends MySqlIntegrationTest {
    private static final Instant START = Instant.parse("2026-07-25T04:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountRepository users;
    @Autowired private PasswordEncoder passwords;

    private UserAccount developer;
    private UserAccount otherDeveloper;
    private UserAccount operator;
    private UserAccount leader;

    @BeforeEach
    void resetFixture() {
        truncateAuditLogs();
        jdbc.update("DELETE FROM notification_events");
        jdbc.update("DELETE FROM assignment_histories");
        jdbc.update("DELETE FROM tasks");
        users.findAll().stream().filter(user -> !user.username().equals("test-bootstrap-leader"))
                .forEach(users::delete);
        developer = account("query-developer", RoleName.DEVELOPER);
        otherDeveloper = account("query-other", RoleName.DEVELOPER);
        operator = account("query-operator", RoleName.OPERATOR);
        leader = account("query-leader", RoleName.LEADER);
    }

    @Test
    void developerSeesOnlyOwnTasksAndCannotReadAnotherDevelopersDetail() throws Exception {
        Task own = seedTask(developer, operator, "VERSION_RELEASE", "Billing", "PENDING", START);
        Task other = seedTask(otherDeveloper, operator, "DATA_MAINTENANCE", "Ledger", "PENDING", START.plusSeconds(3600));

        mvc.perform(get("/api/tasks").param("creatorId", otherDeveloper.id().toString())
                        .with(authentication(authenticationFor(developer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(own.id().toString()));
        mvc.perform(get("/api/tasks/{id}", other.id()).with(authentication(authenticationFor(developer))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void operatorAndLeaderSeeAllTasksAndOperatorCanFilterByOperationDate() throws Exception {
        seedTask(developer, operator, "VERSION_RELEASE", "Billing", "PENDING", START);
        seedTask(otherDeveloper, operator, "DATA_MAINTENANCE", "Ledger", "PENDING", START.plusSeconds(86400));

        mvc.perform(get("/api/tasks").param("operationDate", "2026-07-25")
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/tasks").with(authentication(authenticationFor(leader))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void filtersPageAndSortTaskRowsDeterministically() throws Exception {
        Task release = seedTask(developer, operator, "VERSION_RELEASE", "Billing Core", "PENDING", START);
        Task maintenance = seedTask(otherDeveloper, leader, "DATA_MAINTENANCE", "Billing_Archive", "IN_PROGRESS", START.plusSeconds(3600));
        seedTask(otherDeveloper, operator, "DATA_MAINTENANCE", "Ledger", "COMPLETED", START.plusSeconds(7200));

        mvc.perform(get("/api/tasks").param("category", "VERSION_RELEASE").param("status", "PENDING")
                        .param("assigneeId", operator.id().toString()).param("systemName", "Billing")
                        .param("page", "0").param("size", "1").param("sort", "operationStart,desc")
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.content[0].id").value(release.id().toString()));
        mvc.perform(get("/api/tasks").param("systemName", "Billing_")
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(maintenance.id().toString()));
        mvc.perform(get("/api/tasks").param("sort", "not-a-column,desc")
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(release.id().toString()));
    }

    @Test
    void systemNameSuggestionsAreDistinctEscapedCappedAndRoleScoped() throws Exception {
        seedTask(developer, operator, "VERSION_RELEASE", "Bill%ing", "PENDING", START);
        seedTask(developer, operator, "VERSION_RELEASE", "Bill%ing", "PENDING", START.plusSeconds(3600));
        seedTask(developer, operator, "VERSION_RELEASE", "Billing Own", "PENDING", START.plusSeconds(5400));
        seedTask(otherDeveloper, operator, "VERSION_RELEASE", "Billing Other", "PENDING", START.plusSeconds(7200));
        for (int index = 0; index < 25; index++) {
            seedTask(developer, operator, "VERSION_RELEASE", "Alpha-" + index, "PENDING", START.plusSeconds(10800L + index));
        }

        mvc.perform(get("/api/tasks/system-names").param("query", "Bill%").param("limit", "99")
                        .with(authentication(authenticationFor(developer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value("Bill%ing"));
        mvc.perform(get("/api/tasks/system-names").param("query", "Billing")
                        .with(authentication(authenticationFor(developer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value("Billing Own"));
        mvc.perform(get("/api/tasks/system-names").param("query", "Billing")
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("Billing Other"))
                .andExpect(jsonPath("$[1]").value("Billing Own"));
        mvc.perform(get("/api/tasks/system-names").param("query", "Alpha").param("limit", "99")
                        .with(authentication(authenticationFor(developer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(20));
        mvc.perform(get("/api/tasks/system-names").param("query", "B")
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void detailIncludesOrderedAssignmentTimelineAndAssigneeActionFlags() throws Exception {
        Task pending = seedTask(developer, operator, "VERSION_RELEASE", "Billing", "PENDING", START);
        Task inProgress = seedTask(developer, operator, "VERSION_RELEASE", "Ledger", "IN_PROGRESS", START.plusSeconds(3600));
        Task completed = seedTask(developer, operator, "VERSION_RELEASE", "Payments", "COMPLETED", START.plusSeconds(7200));
        insertHistory(pending.id(), null, operator.id(), START.plusSeconds(20), "first");
        insertHistory(pending.id(), operator.id(), operator.id(), START.plusSeconds(10), "earlier");

        mvc.perform(get("/api/tasks/{id}", pending.id()).with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canCall").value(true))
                .andExpect(jsonPath("$.canComplete").value(false)).andExpect(jsonPath("$.canTransfer").value(true))
                .andExpect(jsonPath("$.assignmentTimeline.length()").value(2))
                .andExpect(jsonPath("$.assignmentTimeline[0].reason").value("earlier"));
        mvc.perform(get("/api/tasks/{id}", inProgress.id()).with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canCall").value(false))
                .andExpect(jsonPath("$.canComplete").value(true)).andExpect(jsonPath("$.canTransfer").value(true));
        mvc.perform(get("/api/tasks/{id}", completed.id()).with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canCall").value(false))
                .andExpect(jsonPath("$.canComplete").value(false)).andExpect(jsonPath("$.canTransfer").value(false));
        mvc.perform(get("/api/tasks/{id}", pending.id()).with(authentication(authenticationFor(leader))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canCall").value(false))
                .andExpect(jsonPath("$.canTransfer").value(false));
    }

    @Test
    void taskCenterRowsAndDetailExposeManualAttentionFlag() throws Exception {
        Task pending = seedTask(
                developer, operator, "VERSION_RELEASE", "Billing",
                "PENDING", START);
        jdbc.update("""
                UPDATE tasks SET needs_manual_attention = TRUE
                WHERE id = UUID_TO_BIN(?)
                """, pending.id().toString());

        mvc.perform(get("/api/tasks")
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].needsManualAttention").value(true));
        mvc.perform(get("/api/tasks/{id}", pending.id())
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsManualAttention").value(true));
    }

    @Test
    void taskCountsAggregateTheWholeVisibleOperationDate() throws Exception {
        Task pending = seedTask(
                developer, operator, "VERSION_RELEASE", "Billing",
                "PENDING", START);
        seedTask(
                developer, operator, "DATA_MAINTENANCE", "Ledger",
                "IN_PROGRESS", START.plusSeconds(3600));
        seedTask(
                otherDeveloper, operator, "VERSION_RELEASE", "Payments",
                "PENDING", START.plusSeconds(7200));
        jdbc.update("""
                UPDATE tasks SET needs_manual_attention = TRUE
                WHERE id = UUID_TO_BIN(?)
                """, pending.id().toString());

        mvc.perform(get("/api/tasks/counts")
                        .param("operationDate", "2026-07-25")
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.inProgress").value(1))
                .andExpect(jsonPath("$.manualAttention").value(1));
        mvc.perform(get("/api/tasks/counts")
                        .param("operationDate", "2026-07-25")
                        .with(authentication(authenticationFor(developer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.inProgress").value(1))
                .andExpect(jsonPath("$.manualAttention").value(1));
    }

    @Test
    void queryEndpointsRequireAuthenticationAndCreateRemainsDeveloperOnly() throws Exception {
        mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tasks").with(csrf()).with(authentication(authenticationFor(operator)))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidQueryValuesReturnStableTaskRequestErrors() throws Exception {
        mvc.perform(get("/api/tasks").param("operationDate", "not-a-date")
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TASK_REQUEST"));
        mvc.perform(get("/api/tasks").param("page", "-1").with(authentication(authenticationFor(operator))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TASK_REQUEST"));
        mvc.perform(get("/api/tasks").param("page", "21474836").param("size", "100")
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(21474836));
        mvc.perform(get("/api/tasks").param("page", "21474837").param("size", "100")
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TASK_REQUEST"));
    }

    private Task seedTask(UserAccount creator, UserAccount assignee, String category, String systemName, String status, Instant start) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (id, ticket_number, category, system_name, estimated_minutes, process_number,
                    operation_date, operation_start_at, operation_end_at, creator_id, current_assignee_id, status,
                    auto_assignment_rule, auto_assignment_explanation, version, actual_start_at, actual_end_at, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), ?, ?, ?, 60, ?, ?, ?, ?, UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'DAY_SECOND',
                    'seed', 2, CASE WHEN ? = 'COMPLETED' THEN ? ELSE NULL END, CASE WHEN ? = 'COMPLETED' THEN ? ELSE NULL END, ?, ?)
                """, id.toString(), "OPS-" + id.toString().replace("-", "").substring(0, 28), category, systemName,
                "PROC-" + id.toString().substring(0, 8), start.toString().substring(0, 10), Timestamp.from(start),
                Timestamp.from(start.plusSeconds(1800)), creator.id().toString(), assignee.id().toString(), status, status,
                Timestamp.from(start), status, Timestamp.from(start.plusSeconds(1800)), Timestamp.from(start), Timestamp.from(start));
        return new Task(id);
    }

    private void insertHistory(UUID taskId, UUID oldAssignee, UUID newAssignee, Instant assignedAt, String reason) {
        jdbc.update("""
                INSERT INTO assignment_histories (id, task_id, assignment_type, old_assignee_id, new_assignee_id,
                    assignment_rule, reason, candidate_snapshot, actor_id, assigned_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'AUTO', UUID_TO_BIN(?), UUID_TO_BIN(?), 'DAY_SECOND', ?,
                    JSON_OBJECT(), UUID_TO_BIN(?), ?)
                """, UUID.randomUUID().toString(), taskId.toString(), oldAssignee == null ? null : oldAssignee.toString(),
                newAssignee.toString(), reason, newAssignee.toString(), Timestamp.from(assignedAt));
    }

    private UserAccount account(String username, RoleName role) {
        return users.saveAndFlush(UserAccount.create(username, username, passwords.encode("Task-Query-Password-1"), Set.of(role), false));
    }

    private UsernamePasswordAuthenticationToken authenticationFor(UserAccount account) {
        CurrentUser user = new CurrentUser(account.id(), account.username(), account.displayName(), account.roles(), false);
        return new UsernamePasswordAuthenticationToken(user, null, account.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList());
    }

    private record Task(UUID id) { }
}
