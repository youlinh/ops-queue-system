package com.acme.opsqueue.task;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice(assignableTypes = TaskController.class)
public class TaskExceptionHandler {
    @ExceptionHandler({
            InvalidTaskRequestException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<ErrorResponse> invalidRequest(Exception exception) {
        return error(
                HttpStatus.BAD_REQUEST,
                "INVALID_TASK_REQUEST",
                "Task request validation failed");
    }

    @ExceptionHandler(MissingDutyRosterException.class)
    ResponseEntity<ErrorResponse> missingRoster(MissingDutyRosterException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "DUTY_ROSTER_MISSING", exception.getMessage());
    }

    @ExceptionHandler(MissingNextDayDutyRosterException.class)
    ResponseEntity<ErrorResponse> missingNextRoster(
            MissingNextDayDutyRosterException exception) {
        return error(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "NEXT_DAY_DUTY_ROSTER_MISSING",
                exception.getMessage());
    }

    @ExceptionHandler(StaleDutyRosterException.class)
    ResponseEntity<ErrorResponse> staleRoster(StaleDutyRosterException exception) {
        return error(HttpStatus.CONFLICT, "DUTY_ROSTER_STALE", exception.getMessage());
    }

    @ExceptionHandler(NoTaskAssigneeException.class)
    ResponseEntity<ErrorResponse> noAssignee(NoTaskAssigneeException exception) {
        return error(HttpStatus.CONFLICT, "NO_ELIGIBLE_ASSIGNEE", exception.getMessage());
    }

    @ExceptionHandler(TaskCreationUnavailableException.class)
    ResponseEntity<ErrorResponse> unavailable(TaskCreationUnavailableException exception) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TASK_CREATION_UNAVAILABLE",
                "Task creation is temporarily unavailable");
    }

    private ResponseEntity<ErrorResponse> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }

    public record ErrorResponse(String code, String message) {
    }
}
