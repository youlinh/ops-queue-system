package com.acme.opsqueue.roster;

import java.time.LocalDate;
import java.util.UUID;

public record DutyRosterView(LocalDate dutyDate, UUID secondLineUserId, UUID thirdLineUserId) {
    static DutyRosterView from(DutyRoster roster) {
        return new DutyRosterView(roster.dutyDate(), roster.secondLineId(), roster.thirdLineId());
    }
}
