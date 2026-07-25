package com.acme.opsqueue.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.opsqueue.scheduling.AssignmentRule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class CreateTaskServiceRetryTest {
    private static final UUID CREATOR =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNEE =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-25T01:00:00Z");

    @Test
    void retriesTheEntireTransactionAtMostThreeTimesAndReturnsOnlyOneSuccess() {
        TaskCreationTransaction transaction = mock(TaskCreationTransaction.class);
        CreatedTask expected = new CreatedTask(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "OPS-20260725-0001",
                ASSIGNEE,
                AssignmentRule.DAY_SECOND);
        when(transaction.execute(any(), eq(CREATOR), eq(SUBMITTED_AT)))
                .thenThrow(new CannotAcquireLockException("deadlock one"))
                .thenThrow(new CannotAcquireLockException("deadlock two"))
                .thenReturn(expected);

        CreateTaskService service = new CreateTaskService(transaction);

        assertThat(service.create(validCommand(), CREATOR, SUBMITTED_AT))
                .isEqualTo(expected);
        verify(transaction, times(3)).execute(any(), eq(CREATOR), eq(SUBMITTED_AT));
    }

    @Test
    void stopsAfterThreeRetryableFailuresWithoutPersistingAReportedSuccess() {
        TaskCreationTransaction transaction = mock(TaskCreationTransaction.class);
        when(transaction.execute(any(), eq(CREATOR), eq(SUBMITTED_AT)))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        CreateTaskService service = new CreateTaskService(transaction);

        assertThatThrownBy(() -> service.create(validCommand(), CREATOR, SUBMITTED_AT))
                .isInstanceOf(TaskCreationUnavailableException.class)
                .hasMessageContaining("three attempts");
        verify(transaction, times(3)).execute(any(), eq(CREATOR), eq(SUBMITTED_AT));
    }

    private CreateTaskCommand validCommand() {
        return new CreateTaskCommand(
                TaskCategory.VERSION_RELEASE,
                "Billing",
                30,
                "PROC-1",
                Instant.parse("2026-07-25T02:00:00Z"),
                Instant.parse("2026-07-25T03:00:00Z"));
    }
}
