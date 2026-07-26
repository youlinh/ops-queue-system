package com.acme.opsqueue.assignment;

public final class AssignmentValidationException extends RuntimeException {
    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        FORBIDDEN,
        CONFLICT,
        INVALID_TARGET
    }

    private final Reason reason;

    private AssignmentValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    static AssignmentValidationException invalidRequest(String message) {
        return new AssignmentValidationException(Reason.INVALID_REQUEST, message);
    }

    static AssignmentValidationException notFound(String message) {
        return new AssignmentValidationException(Reason.NOT_FOUND, message);
    }

    static AssignmentValidationException forbidden(String message) {
        return new AssignmentValidationException(Reason.FORBIDDEN, message);
    }

    static AssignmentValidationException conflict(String message) {
        return new AssignmentValidationException(Reason.CONFLICT, message);
    }

    static AssignmentValidationException invalidTarget(String message) {
        return new AssignmentValidationException(Reason.INVALID_TARGET, message);
    }
}
