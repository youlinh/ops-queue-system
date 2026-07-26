package com.acme.opsqueue.assignment;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "ops.audit.reconciliation.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RedistributionAuditReconciler {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedistributionAuditReconciler.class);
    private final RedistributionAuditTransaction auditTransactions;
    private final Clock clock;

    public RedistributionAuditReconciler(
            RedistributionAuditTransaction auditTransactions, Clock clock) {
        this.auditTransactions = auditTransactions;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${ops.audit.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${ops.audit.reconciliation.delay-ms:60000}")
    public void reconcile() {
        for (var commandId : auditTransactions.readyCommandIds()) {
            try {
                auditTransactions.finalizeCommand(commandId);
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Unable to reconcile redistribution audit command {}",
                        commandId,
                        exception);
            }
        }
        Instant now = clock.instant();
        for (var commandId : auditTransactions.expiredRunningCommandIds(now)) {
            try {
                auditTransactions.abandonCommand(commandId, now);
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Unable to mark interrupted redistribution command {}",
                        commandId,
                        exception);
            }
        }
    }
}
