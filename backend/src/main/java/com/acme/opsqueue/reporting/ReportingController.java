package com.acme.opsqueue.reporting;

import com.acme.opsqueue.identity.CurrentUser;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportingController {
    private final ReportingService reports;

    public ReportingController(ReportingService reports) {
        this.reports = reports;
    }

    @GetMapping("/daily")
    public DailyOperatorReport daily(
            @RequestParam LocalDate date,
            @RequestParam UUID operatorId,
            @AuthenticationPrincipal CurrentUser requester) {
        return reports.daily(date, operatorId, requester);
    }

    @GetMapping("/monthly")
    public MonthlyOperatorReport monthly(
            @RequestParam YearMonth month,
            @RequestParam UUID operatorId,
            @AuthenticationPrincipal CurrentUser requester) {
        return reports.monthly(month, operatorId, requester);
    }
}
