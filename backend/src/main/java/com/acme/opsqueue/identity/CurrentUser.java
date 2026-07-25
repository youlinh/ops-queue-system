package com.acme.opsqueue.identity;

import java.util.Set;
import java.util.UUID;

public record CurrentUser(
        UUID id,
        String username,
        String displayName,
        Set<RoleName> roles,
        boolean mustChangePassword) {

    public CurrentUser {
        roles = Set.copyOf(roles);
    }
}
