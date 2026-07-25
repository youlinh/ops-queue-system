package com.acme.opsqueue.task;

import java.time.LocalDate;

public class MissingDutyRosterException extends RuntimeException {
    public MissingDutyRosterException(LocalDate date) {
        super("No duty roster exists for operation date " + date);
    }
}
