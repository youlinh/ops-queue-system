package com.acme.opsqueue.roster;

import java.time.LocalDate;
import java.util.UUID;

public record RosterImportPreviewRow(
        int sourceRowNumber,
        LocalDate dutyDate,
        UUID secondLineUserId,
        String secondLineDisplayName,
        UUID thirdLineUserId,
        String thirdLineDisplayName) {
}
