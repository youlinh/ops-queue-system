package com.acme.opsqueue.assignment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = AssignmentController.class)
public class AssignmentExceptionHandler {
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> malformed(Exception exception) {
        return error(
                HttpStatus.BAD_REQUEST,
                "INVALID_ASSIGNMENT_REQUEST",
                "Assignment request validation failed");
    }

    @ExceptionHandler(AssignmentValidationException.class)
    ResponseEntity<ErrorResponse> assignment(
            AssignmentValidationException exception) {
        return switch (exception.reason()) {
            case INVALID_REQUEST -> error(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ASSIGNMENT_REQUEST",
                    exception.getMessage());
            case NOT_FOUND -> error(
                    HttpStatus.NOT_FOUND,
                    "ASSIGNMENT_TASK_NOT_FOUND",
                    exception.getMessage());
            case FORBIDDEN -> error(
                    HttpStatus.FORBIDDEN,
                    "ASSIGNMENT_FORBIDDEN",
                    exception.getMessage());
            case CONFLICT -> error(
                    HttpStatus.CONFLICT,
                    "ASSIGNMENT_CONFLICT",
                    exception.getMessage());
            case INVALID_TARGET -> error(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_ASSIGNMENT_TARGET",
                    exception.getMessage());
        };
    }

    private ResponseEntity<ErrorResponse> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message));
    }

    record ErrorResponse(String code, String message) {
    }
}
