package com.acme.opsqueue.identity;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IdentityExceptionHandler {
    @ExceptionHandler({
            OptimisticLockingFailureException.class,
            DataIntegrityViolationException.class
    })
    ResponseEntity<Void> conflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
