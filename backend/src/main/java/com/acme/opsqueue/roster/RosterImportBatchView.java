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
        long errorCount) {
}
