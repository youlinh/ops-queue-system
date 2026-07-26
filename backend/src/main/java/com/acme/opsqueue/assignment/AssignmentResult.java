package com.acme.opsqueue.assignment;

import java.util.List;
import java.util.UUID;

public record AssignmentResult(
        UUID taskId,
        UUID previousAssigneeId,
        UUID assigneeId,
        List<String> warnings,
        long version) {
    public AssignmentResult {
        warnings = List.copyOf(warnings);
    }
}
