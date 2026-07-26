package com.acme.opsqueue.notification;

import com.acme.opsqueue.identity.CurrentUser;
import java.time.Clock;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationClaimService notifications;
    private final Clock clock;

    public NotificationController(
            NotificationClaimService notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * POST rather than GET because claiming mutates event state; this keeps
     * the endpoint behind the regular CSRF protection.
     */
    @PostMapping("/claim")
    public List<NotificationClaimService.ClaimedNotification> claim(
            @AuthenticationPrincipal CurrentUser currentUser) {
        return notifications.claimPending(currentUser.id(), clock.instant());
    }
}
