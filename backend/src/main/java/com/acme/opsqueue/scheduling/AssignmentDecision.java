package com.acme.opsqueue.scheduling;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AssignmentDecision(
        AssignmentRule rule,
        UUID assigneeId,
        List<CandidateSnapshot> candidates,
        String explanation) {

    public AssignmentDecision {
        Objects.requireNonNull(rule, "rule must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("explanation must not be blank");
        }
    }
}
