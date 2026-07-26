package com.acme.opsqueue.task;

public class TaskLifecycleException extends RuntimeException {
    public enum Reason { NOT_FOUND, FORBIDDEN, CONFLICT, INVALID_DURATION, INVALID_REQUEST }

    private final Reason reason;

    private TaskLifecycleException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static TaskLifecycleException notFound() {
        return new TaskLifecycleException(Reason.NOT_FOUND, "Task was not found");
    }

    public static TaskLifecycleException forbidden() {
        return new TaskLifecycleException(Reason.FORBIDDEN, "Only the current assignee may change this task");
    }

    public static TaskLifecycleException conflict(String message) {
        return new TaskLifecycleException(Reason.CONFLICT, message);
    }

    public static TaskLifecycleException invalidDuration() {
        return new TaskLifecycleException(
                Reason.INVALID_DURATION,
                "Actual minutes must be between 1 and "
                        + TaskLifecycleService.MAX_ACTUAL_MINUTES);
    }

    public static TaskLifecycleException invalidRequest(String message) {
        return new TaskLifecycleException(Reason.INVALID_REQUEST, message);
    }

    public Reason reason() {
        return reason;
    }
}
