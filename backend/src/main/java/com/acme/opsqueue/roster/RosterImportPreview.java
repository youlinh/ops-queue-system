package com.acme.opsqueue.roster;

import java.util.List;
import java.util.UUID;

public record RosterImportPreview(UUID batchId, boolean valid, List<RosterImportError> errors) {
    public RosterImportPreview {
        errors = List.copyOf(errors);
    }
}
