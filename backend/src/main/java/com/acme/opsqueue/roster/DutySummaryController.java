package com.acme.opsqueue.roster;

import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/duty")
public class DutySummaryController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final DutyRosterRepository rosters;
    private final UserAccountRepository users;
    private final Clock clock;

    public DutySummaryController(
            DutyRosterRepository rosters,
            UserAccountRepository users,
            Clock clock) {
        this.rosters = rosters;
        this.users = users;
        this.clock = clock;
    }

    @GetMapping("/today")
    @Transactional(readOnly = true)
    public DutySummaryView today() {
        LocalDate today = clock.instant().atZone(BUSINESS_ZONE).toLocalDate();
        return rosters.findByDutyDate(today)
                .map(roster -> new DutySummaryView(
                        today,
                        true,
                        dutyUser(roster.secondLineId()),
                        dutyUser(roster.thirdLineId())))
                .orElseGet(() -> new DutySummaryView(
                        today, false, null, null));
    }

    private DutyUserView dutyUser(UUID userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Duty roster references a missing user"));
        return new DutyUserView(user.id(), user.displayName());
    }

    public record DutySummaryView(
            LocalDate dutyDate,
            boolean configured,
            DutyUserView secondLine,
            DutyUserView thirdLine) {
    }

    public record DutyUserView(UUID id, String displayName) {
    }
}
