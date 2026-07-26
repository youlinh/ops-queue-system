package com.acme.opsqueue.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.opsqueue.assignment.AssignmentController;
import com.acme.opsqueue.assignment.AssignmentResult;
import com.acme.opsqueue.assignment.AssignmentService;
import com.acme.opsqueue.assignment.RedistributionItemResult;
import com.acme.opsqueue.assignment.RedistributionResult;
import com.acme.opsqueue.assignment.RedistributionService;
import com.acme.opsqueue.assignment.UnavailabilityView;
import com.acme.opsqueue.identity.ClientIpResolver;
import com.acme.opsqueue.identity.CurrentUser;
import com.acme.opsqueue.identity.IdentityController;
import com.acme.opsqueue.identity.IdentityService;
import com.acme.opsqueue.identity.JwtCookieService;
import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.roster.RosterController;
import com.acme.opsqueue.roster.RosterImportService;
import com.acme.opsqueue.scheduling.AssignmentRule;
import com.acme.opsqueue.task.CreateTaskService;
import com.acme.opsqueue.task.CreatedTask;
import com.acme.opsqueue.task.TaskCategory;
import com.acme.opsqueue.task.TaskController;
import com.acme.opsqueue.task.TaskLifecycleService;
import com.acme.opsqueue.task.TaskQueryService;
import com.acme.opsqueue.task.TaskView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

class CommandAuditWiringTest {
    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AuditService audits = mock(AuditService.class);

    @Test
    void controllerOwnedSuccessfulCommandsEmitExactlyOneActionLevelAudit() {
        CurrentUser leader = currentUser(RoleName.LEADER);
        CurrentUser operator = currentUser(RoleName.OPERATOR);

        exerciseIdentityCommands(leader, operator);
        exerciseRosterCommand(leader);
        exerciseTaskCommands(operator);
        exerciseAssignmentCommands(leader, operator);

        ArgumentCaptor<String> loginAction = ArgumentCaptor.forClass(String.class);
        verify(audits).record(
                any(), loginAction.capture(), any(), any(), any(), any(), any(), any());
        assertThat(loginAction.getValue()).isEqualTo("LOGIN_SUCCESS");

        ArgumentCaptor<String> commandActions = ArgumentCaptor.forClass(String.class);
        verify(audits, times(11)).recordCurrentRequest(
                any(), commandActions.capture(), any(), any(), any(), any(), any());
        assertThat(commandActions.getAllValues()).containsExactlyInAnyOrder(
                "ACCOUNT_CREATED",
                "ACCOUNT_DISABLED",
                "ACCOUNT_PASSWORD_RESET",
                "ACCOUNT_ROLES_CHANGED",
                "ROSTER_CONFIRMED",
                "TASK_CALLED",
                "TASK_COMPLETED",
                "TASK_TRANSFERRED",
                "TASK_LEADER_ADJUSTED",
                "UNAVAILABILITY_CREATED",
                "UNAVAILABILITY_REMOVED");
    }

    private void exerciseIdentityCommands(CurrentUser leader, CurrentUser operator) {
        IdentityService identities = mock(IdentityService.class);
        JwtCookieService jwtCookies = mock(JwtCookieService.class);
        ClientIpResolver clientIps = mock(ClientIpResolver.class);
        CookieCsrfTokenRepository csrfTokens = mock(CookieCsrfTokenRepository.class);
        IdentityController controller = new IdentityController(
                identities, jwtCookies, clientIps, csrfTokens, audits, CLOCK);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        UserAccount created = UserAccount.create(
                "created-user", "Created User", "encoded",
                Set.of(RoleName.OPERATOR), true);

        when(clientIps.resolve(request)).thenReturn("192.0.2.10");
        when(identities.authenticate("operator", "valid-password", "192.0.2.10"))
                .thenReturn(operator);
        when(identities.create(any(), any(), any(), any())).thenReturn(created);
        when(identities.replaceRoles(any(), any())).thenReturn(created);

        controller.login(
                new IdentityController.LoginRequest("operator", "valid-password"),
                request, response);
        controller.createUser(
                leader,
                new IdentityController.CreateUserRequest(
                        "created-user", "Created User", "Initial-Password-1",
                        Set.of(RoleName.OPERATOR)));
        controller.disable(created.id(), leader);
        controller.resetPassword(
                created.id(), leader,
                new IdentityController.ResetPasswordRequest("Reset-Password-1"));
        controller.replaceRoles(
                created.id(), leader,
                new IdentityController.ReplaceRolesRequest(Set.of(RoleName.OPERATOR)));
    }

    private void exerciseRosterCommand(CurrentUser leader) {
        RosterImportService imports = mock(RosterImportService.class);
        RosterController controller = new RosterController(imports, audits, CLOCK);

        controller.confirm(UUID.randomUUID(), leader);
    }

    private void exerciseTaskCommands(CurrentUser operator) {
        CreateTaskService creation = mock(CreateTaskService.class);
        TaskLifecycleService lifecycle = mock(TaskLifecycleService.class);
        TaskController controller = new TaskController(
                creation, lifecycle, mock(TaskQueryService.class), CLOCK, audits);
        UUID taskId = UUID.randomUUID();
        CreatedTask created = new CreatedTask(
                taskId,
                "Q202607250001",
                operator.id(),
                operator.displayName(),
                AssignmentRule.DAY_SECOND);
        TaskView inProgress = taskView(taskId, operator.id(), "IN_PROGRESS", null);
        TaskView completed = taskView(taskId, operator.id(), "COMPLETED", 30);

        when(creation.create(any(), any(), any())).thenReturn(created);
        when(lifecycle.call(any(), any(), any())).thenReturn(inProgress);
        when(lifecycle.complete(any(), any(), anyInt(), any())).thenReturn(completed);

        controller.create(
                operator,
                new TaskController.CreateTaskRequest(
                        TaskCategory.VERSION_RELEASE,
                        "Settlement",
                        30,
                        "FLOW-001",
                        NOW.plusSeconds(3600),
                        NOW.plusSeconds(7200)));
        controller.call(taskId, operator);
        controller.complete(
                taskId, operator, new TaskController.CompleteTaskRequest(30));
    }

    private void exerciseAssignmentCommands(CurrentUser leader, CurrentUser operator) {
        AssignmentService assignments = mock(AssignmentService.class);
        RedistributionService redistribution = mock(RedistributionService.class);
        AssignmentController controller = new AssignmentController(
                assignments, redistribution, CLOCK, audits);
        UUID taskId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 25);
        AssignmentResult assignment = new AssignmentResult(
                taskId, operator.id(), targetId, List.of(), 1);
        UnavailabilityView unavailable = new UnavailabilityView(
                operator.id(), date, "leave", leader.id(), NOW);
        RedistributionResult redistributed = new RedistributionResult(
                operator.id(), date,
                List.of(new RedistributionItemResult(
                        taskId, "Q202607250001", true, operator.id(),
                        targetId, false, null)));

        when(assignments.transfer(any(), any(), any(), any(), any()))
                .thenReturn(assignment);
        when(assignments.leaderAdjust(any(), any(), any(), any(), any()))
                .thenReturn(assignment);
        when(assignments.setUnavailable(any(), any(), any(), any(), any()))
                .thenReturn(unavailable);
        when(redistribution.redistribute(any(), any(), any(), any(), any()))
                .thenReturn(redistributed);

        controller.transfer(
                taskId, operator,
                new AssignmentController.AssignmentRequest(targetId, "handoff"));
        controller.adjust(
                taskId, leader,
                new AssignmentController.AssignmentRequest(targetId, "adjust"));
        controller.setUnavailable(
                leader,
                new AssignmentController.UnavailabilityRequest(
                        operator.id(), date, "leave"));
        controller.removeUnavailable(operator.id(), date, leader);
        controller.redistribute(
                leader,
                new AssignmentController.RedistributionRequest(
                        operator.id(), date, "redistribute"));
    }

    private CurrentUser currentUser(RoleName role) {
        UUID id = UUID.randomUUID();
        return new CurrentUser(
                id, role.name().toLowerCase(), role.name(), Set.of(role), false);
    }

    private TaskView taskView(
            UUID taskId, UUID assigneeId, String status, Integer actualMinutes) {
        return new TaskView(
                taskId,
                "Q202607250001",
                TaskCategory.VERSION_RELEASE,
                "Settlement",
                "FLOW-001",
                NOW.plusSeconds(3600),
                NOW.plusSeconds(7200),
                UUID.randomUUID(),
                assigneeId,
                status,
                NOW,
                assigneeId,
                actualMinutes,
                "COMPLETED".equals(status) ? NOW.plusSeconds(1800) : null,
                "COMPLETED".equals(status) ? assigneeId : null,
                1);
    }
}
