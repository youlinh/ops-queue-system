package com.acme.opsqueue.task;

public class InvalidTaskRequestException extends RuntimeException {
    public InvalidTaskRequestException(String message) {
        super(message);
    }
}
