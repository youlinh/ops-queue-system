package com.acme.opsqueue.task;

import com.acme.opsqueue.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final CreateTaskService taskCreation;
    private final TaskLifecycleService lifecycle;
    private final Clock clock;

    public TaskController(
            CreateTaskService taskCreation,
            TaskLifecycleService lifecycle,
            Clock clock) {
        this.taskCreation = taskCreation;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedTask create(
            @AuthenticationPrincipal CurrentUser creator,
            @Valid @RequestBody CreateTaskRequest request) {
        return taskCreation.create(
                request.toCommand(), creator.id(), clock.instant());
    }

    @PostMapping("/{id}/call")
    public TaskView call(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal CurrentUser actor) {
        return lifecycle.call(id, actor.id(), clock.instant());
    }

    @PostMapping("/{id}/complete")
    public TaskView complete(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal CurrentUser actor,
            @RequestBody CompleteTaskRequest request) {
        if (request == null || request.actualMinutes() == null) {
            throw TaskLifecycleException.invalidDuration();
        }
        return lifecycle.complete(id, actor.id(), request.actualMinutes(), clock.instant());
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
