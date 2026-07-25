package com.acme.opsqueue.task;

public class NoTaskAssigneeException extends RuntimeException {
    public NoTaskAssigneeException() {
        super("No eligible operator is available for this task");
    }
}
