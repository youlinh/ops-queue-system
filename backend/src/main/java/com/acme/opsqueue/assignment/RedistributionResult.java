package com.acme.opsqueue.assignment;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RedistributionResult(
        UUID sourceOperatorId,
        LocalDate date,
        List<RedistributionItemResult> items) {
    public RedistributionResult {
        items = List.copyOf(items);
    }
}
