package com.acme.opsqueue.scheduling;

import java.util.Objects;
import java.util.UUID;

public record DutyPair(UUID secondLineUserId, UUID thirdLineUserId) {

    public DutyPair {
        Objects.requireNonNull(secondLineUserId, "secondLineUserId must not be null");
        Objects.requireNonNull(thirdLineUserId, "thirdLineUserId must not be null");
        if (secondLineUserId.equals(thirdLineUserId)) {
            throw new IllegalArgumentException("second- and third-line users must be different");
        }
    }
}
