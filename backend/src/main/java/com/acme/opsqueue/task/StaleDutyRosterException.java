package com.acme.opsqueue.task;

import java.time.LocalDate;

public class StaleDutyRosterException extends RuntimeException {
    public StaleDutyRosterException(LocalDate date) {
        super("Duty roster for " + date + " contains a user who is not an active operator");
    }
}
