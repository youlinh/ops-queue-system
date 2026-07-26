package com.acme.opsqueue.reporting;

import java.time.YearMonth;
import java.util.UUID;

public record MonthlyOperatorReport(
        UUID operatorId,
        YearMonth month,
        long totalTaskCount,
        long pendingCount,
        long inProgressCount,
        long completedCount,
        long estimatedMinutes,
        long completedActualMinutes) {
}
