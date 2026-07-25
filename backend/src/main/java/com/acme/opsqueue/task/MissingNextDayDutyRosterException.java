package com.acme.opsqueue.task;

import java.time.LocalDate;

public class MissingNextDayDutyRosterException extends RuntimeException {
    public MissingNextDayDutyRosterException(LocalDate date) {
        super("Fair allocation requires a duty roster for " + date);
    }
}
