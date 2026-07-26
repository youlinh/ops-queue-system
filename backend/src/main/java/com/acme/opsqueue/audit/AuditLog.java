package com.acme.opsqueue.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLog(
        UUID id,
        UUID actorId,
        String action,
        String objectType,
        UUID objectId,
        Map<String, Object> before,
        Map<String, Object> after,
        String sourceIp,
        Instant occurredAt) {
}
