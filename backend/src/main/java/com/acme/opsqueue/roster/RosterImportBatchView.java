package com.acme.opsqueue.roster;

import java.time.Instant;
import java.util.UUID;

public record RosterImportBatchView(
        UUID id,
        RosterImportBatch.Status status,
        String originalFilename,
        String fileSha256,
        int rowCount,
        UUID uploadedByUserId,
        Instant createdAt,
        UUID importedByUserId,
        Instant importedAt,
        String coveredDates,
        java.util.List<RosterImportError> errors) {
    static RosterImportBatchView from(RosterImportBatch batch) {
        return new RosterImportBatchView(batch.id(), batch.status(), batch.originalFilename(),
                batch.fileSha256(), batch.rowCount(), batch.uploadedByUserId(), batch.createdAt(),
                batch.importedByUserId(), batch.importedAt(), batch.coveredDates(),
                batch.errors().stream().map(error -> new RosterImportError(error.sourceRowNumber(), error.message())).toList());
    }
}
