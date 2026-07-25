package com.acme.opsqueue.roster;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RosterImportBatchDetailView(UUID id, RosterImportBatch.Status status, String originalFilename,
        String fileSha256, int rowCount, UUID uploadedByUserId, Instant createdAt, UUID importedByUserId,
        Instant importedAt, String coveredDates, List<RosterImportError> errors) {
    static RosterImportBatchDetailView from(RosterImportBatch batch) {
        return new RosterImportBatchDetailView(batch.id(), batch.status(), batch.originalFilename(), batch.fileSha256(),
                batch.rowCount(), batch.uploadedByUserId(), batch.createdAt(), batch.importedByUserId(), batch.importedAt(),
                batch.coveredDates(), batch.errors().stream()
                        .map(error -> new RosterImportError(error.sourceRowNumber(), error.message())).toList());
    }
}
