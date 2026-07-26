package com.acme.opsqueue.reporting;

import java.time.LocalDate;
import java.util.UUID;

public record DailyOperatorReport(
        UUID operatorId,
        LocalDate date,
        long totalTaskCount,
        long pendingCount,
        long inProgressCount,
        long completedCount,
        long estimatedMinutes,
        long completedActualMinutes) {
}
