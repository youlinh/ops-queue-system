package com.acme.opsqueue.task;

import com.acme.opsqueue.audit.AuditService;
import com.acme.opsqueue.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final CreateTaskService taskCreation;
    private final TaskLifecycleService lifecycle;
    private final TaskQueryService taskQueries;
    private final Clock clock;
    private final AuditService audits;

    public TaskController(
            CreateTaskService taskCreation,
            TaskLifecycleService lifecycle,
            TaskQueryService taskQueries,
            Clock clock,
            AuditService audits) {
        this.taskCreation = taskCreation;
        this.lifecycle = lifecycle;
        this.taskQueries = taskQueries;
        this.clock = clock;
        this.audits = audits;
    }

    @GetMapping
    public TaskPage<TaskQueryService.TaskRow> search(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) LocalDate operationDate,
            @RequestParam(required = false) TaskCategory category,
            @RequestParam(required = false) String systemName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID creatorId,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return taskQueries.search(
                new TaskQuery(
                        operationDate,
                        category,
                        systemName,
                        status,
                        creatorId,
                        assigneeId,
                        page,
                        size,
                        sort),
                currentUser);
    }

    @GetMapping("/system-names")
    public List<String> systemNames(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer limit) {
        return taskQueries.systemNames(query, limit, currentUser);
    }

    @GetMapping("/counts")
    public TaskQueryService.TaskCounts counts(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam LocalDate operationDate) {
        return taskQueries.counts(operationDate, currentUser);
    }

    @GetMapping("/{id}")
    public TaskDetailView detail(
            @PathVariable UUID id,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return taskQueries.detail(id, currentUser);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedTask create(
            @AuthenticationPrincipal CurrentUser creator,
            @Valid @RequestBody CreateTaskRequest request) {
        return taskCreation.create(request.toCommand(), creator.id(), clock.instant());
    }

    @PostMapping("/{id}/call")
    @Transactional
    public TaskView call(
            @PathVariable UUID id,
            @AuthenticationPrincipal CurrentUser actor) {
        Instant at = clock.instant();
        TaskView task = lifecycle.call(id, actor.id(), at);
        audits.recordCurrentRequest(
                actor.id(), "TASK_CALLED", "TASK", id,
                Map.of("status", "PENDING"),
                Map.of("status", task.status(), "assigneeId", task.currentAssigneeId()),
                at);
        return task;
    }

    @PostMapping("/{id}/complete")
    @Transactional
    public TaskView complete(
            @PathVariable UUID id,
            @AuthenticationPrincipal CurrentUser actor,
            @RequestBody CompleteTaskRequest request) {
        if (request == null || request.actualMinutes() == null) {
            throw TaskLifecycleException.invalidDuration();
        }
        Instant at = clock.instant();
        TaskView task =
                lifecycle.complete(id, actor.id(), request.actualMinutes(), at);
        audits.recordCurrentRequest(
                actor.id(), "TASK_COMPLETED", "TASK", id,
                Map.of("status", "IN_PROGRESS"),
                Map.of(
                        "status", task.status(),
                        "assigneeId", task.currentAssigneeId(),
                        "actualMinutes", task.actualMinutes()),
                at);
        return task;
    }

    public record CreateTaskRequest(
            @NotNull TaskCategory category,
            @NotBlank @Size(max = 128) String systemName,
            @NotNull @Positive Integer estimatedMinutes,
            @NotBlank @Size(max = 128) String processNumber,
            @NotNull Instant operationStart,
            @NotNull Instant operationEnd) {

        CreateTaskCommand toCommand() {
            return new CreateTaskCommand(
                    category,
                    systemName,
                    estimatedMinutes,
                    processNumber,
                    operationStart,
                    operationEnd);
        }
    }

    public record CompleteTaskRequest(Integer actualMinutes) {
    }
}
