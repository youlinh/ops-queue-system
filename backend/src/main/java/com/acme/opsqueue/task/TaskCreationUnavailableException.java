package com.acme.opsqueue.task;

public class TaskCreationUnavailableException extends RuntimeException {
    public TaskCreationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
