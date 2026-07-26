package com.acme.opsqueue.roster;

import java.util.List;
import java.util.UUID;

public record RosterImportPreview(
        UUID batchId,
        boolean valid,
        List<RosterImportError> errors,
        List<RosterImportPreviewRow> rows) {
    public RosterImportPreview {
        errors = List.copyOf(errors);
        rows = List.copyOf(rows);
    }
}
