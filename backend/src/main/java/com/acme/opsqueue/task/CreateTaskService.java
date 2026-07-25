package com.acme.opsqueue.task;

import com.acme.opsqueue.scheduling.NoEligibleCandidateException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

@Service
public class CreateTaskService {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_TEXT_LENGTH = 128;

    private final TaskCreationTransaction transaction;

    public CreateTaskService(TaskCreationTransaction transaction) {
        this.transaction = transaction;
    }

    public CreatedTask create(
            CreateTaskCommand command,
            UUID creatorId,
            Instant submittedAt) {
        CreateTaskCommand normalized = validateAndNormalize(command, creatorId, submittedAt);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transaction.execute(normalized, creatorId, submittedAt);
            } catch (NoEligibleCandidateException exception) {
                throw new NoTaskAssigneeException();
            } catch (DataAccessException exception) {
                if (isRetryable(exception) && attempt < MAX_ATTEMPTS) {
                    continue;
                }
                String message = isRetryable(exception)
                        ? "Task creation could not complete after three attempts"
                        : "Task creation could not be persisted";
                throw new TaskCreationUnavailableException(message, exception);
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private CreateTaskCommand validateAndNormalize(
            CreateTaskCommand command,
            UUID creatorId,
            Instant submittedAt) {
        if (command == null) {
            throw new InvalidTaskRequestException("Request body is required");
        }
        if (command.category() == null) {
            throw new InvalidTaskRequestException("Category is required");
        }
        String systemName = normalizeRequired(command.systemName(), "System name");
        String processNumber = normalizeRequired(command.processNumber(), "Process number");
        if (systemName.length() > MAX_TEXT_LENGTH) {
            throw new InvalidTaskRequestException("System name must not exceed 128 characters");
        }
        if (processNumber.length() > MAX_TEXT_LENGTH) {
            throw new InvalidTaskRequestException("Process number must not exceed 128 characters");
        }
        if (command.estimatedMinutes() <= 0) {
            throw new InvalidTaskRequestException("Estimated minutes must be positive");
        }
        if (command.operationStart() == null || command.operationEnd() == null) {
            throw new InvalidTaskRequestException("Operation start and end are required");
        }
        if (!command.operationEnd().isAfter(command.operationStart())) {
            throw new InvalidTaskRequestException(
                    "Operation end must be strictly after operation start");
        }
        if (creatorId == null) {
            throw new InvalidTaskRequestException("Authenticated creator is required");
        }
        if (submittedAt == null) {
            throw new InvalidTaskRequestException("Submission time is required");
        }
        return new CreateTaskCommand(
                command.category(),
                systemName,
                command.estimatedMinutes(),
                processNumber,
                command.operationStart(),
                command.operationEnd());
    }

    private String normalizeRequired(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidTaskRequestException(field + " is required");
        }
        return value.trim();
    }

    private boolean isRetryable(Throwable exception) {
        if (exception instanceof TransientDataAccessException
                || exception instanceof DuplicateKeyException) {
            return true;
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                int code = sqlException.getErrorCode();
                if ("40001".equals(state)
                        || "41000".equals(state)
                        || code == 1062
                        || code == 1205
                        || code == 1213) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
