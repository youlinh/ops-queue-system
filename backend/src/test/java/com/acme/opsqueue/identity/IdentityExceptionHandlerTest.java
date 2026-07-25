package com.acme.opsqueue.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;

class IdentityExceptionHandlerTest {
    @Test
    void optimisticWriteConflictMapsToHttp409() {
        var response = new IdentityExceptionHandler()
                .conflict(new OptimisticLockingFailureException("stale identity update"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
