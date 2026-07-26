package com.acme.opsqueue.identity;

import com.acme.opsqueue.audit.AuditService;
import java.time.Clock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

class IdentityServiceConcurrencyTest {
    @Test
    void oneOffBucketsAreGloballyExpiredWithoutRevisitingTheirKeys() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-07-25T08:00:00Z"));
        IdentityService identities = new IdentityService(users, passwords, clock);

        for (int attempt = 0; attempt < 1_000; attempt++) {
            rejectOrRateLimit(
                    identities,
                    "random-user-" + attempt,
                    "198.51." + (attempt / 256) + "." + (attempt % 256));
        }
        assertThat(identities.throttleUsernameKeyCount()).isEqualTo(1_000);
        assertThat(identities.throttleSourceIpKeyCount()).isEqualTo(1_000);

        clock.advance(Duration.ofMinutes(16));
        rejectOrRateLimit(identities, "fresh-user", "203.0.113.200");

        assertThat(identities.throttleUsernameKeyCount()).isEqualTo(1);
        assertThat(identities.throttleSourceIpKeyCount()).isEqualTo(1);
    }

    @Test
    void oneOffBucketsRemainUnderTheExplicitHardLimit() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        IdentityService identities = new IdentityService(
                users,
                passwords,
                new MutableClock(Instant.parse("2026-07-25T08:00:00Z")));

        for (int attempt = 0; attempt < 5_000; attempt++) {
            rejectOrRateLimit(
                    identities,
                    "capacity-user-" + attempt,
                    "192.0." + (attempt / 256) + "." + (attempt % 256));
        }

        assertThat(identities.throttleUsernameKeyCount()).isLessThanOrEqualTo(4_096);
        assertThat(identities.throttleSourceIpKeyCount()).isLessThanOrEqualTo(4_096);
    }

    @Test
    void backendExceptionsCancelOnlyTheirPendingReservations() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        UserAccount account = UserAccount.create(
                "recovering-account",
                "Recovering Account",
                "stored-hash",
                Set.of(RoleName.DEVELOPER),
                false);
        AtomicBoolean backendOutage = new AtomicBoolean();
        AtomicBoolean encoderOutage = new AtomicBoolean();
        when(users.findByUsername("recovering-account")).thenAnswer(invocation -> {
            if (backendOutage.get()) {
                throw new IllegalStateException("database unavailable");
            }
            return Optional.of(account);
        });
        when(passwords.matches("wrong-password", "stored-hash")).thenAnswer(invocation -> {
            if (encoderOutage.get()) {
                throw new IllegalArgumentException("bcrypt unavailable");
            }
            return false;
        });
        IdentityService identities = new IdentityService(users, passwords);

        assertRejected(identities, "recovering-account", "198.51.100.90");
        backendOutage.set(true);
        for (int outage = 0; outage < 3; outage++) {
            assertThatThrownBy(() -> identities.authenticate(
                            "recovering-account",
                            "wrong-password",
                            "198.51.100.90"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("database unavailable");
        }

        backendOutage.set(false);
        encoderOutage.set(true);
        for (int outage = 0; outage < 2; outage++) {
            assertThatThrownBy(() -> identities.authenticate(
                            "recovering-account",
                            "wrong-password",
                            "198.51.100.90"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("bcrypt unavailable");
        }

        encoderOutage.set(false);
        for (int genuineFailure = 0; genuineFailure < 4; genuineFailure++) {
            assertRejected(identities, "recovering-account", "198.51.100.90");
        }
        assertThatThrownBy(() -> identities.authenticate(
                        "recovering-account",
                        "wrong-password",
                        "198.51.100.90"))
                .isInstanceOf(IdentityService.LoginRateLimitedException.class);
    }

    @Test
    void pendingCredentialChecksDoNotExpireUntilTheyFinish() throws Exception {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        UserAccount account = UserAccount.create(
                "stalled-account",
                "Stalled Account",
                "stored-hash",
                Set.of(RoleName.DEVELOPER),
                false);
        when(users.findByUsername("stalled-account")).thenReturn(Optional.of(account));
        CountDownLatch allCredentialChecksStarted = new CountDownLatch(5);
        CountDownLatch releaseCredentialChecks = new CountDownLatch(1);
        when(passwords.matches("wrong-password", "stored-hash")).thenAnswer(invocation -> {
            allCredentialChecksStarted.countDown();
            if (!releaseCredentialChecks.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Credential checks were not released");
            }
            return false;
        });
        MutableClock clock = new MutableClock(Instant.parse("2026-07-25T08:00:00Z"));
        IdentityService identities = new IdentityService(users, passwords, clock);
        List<Future<?>> pending = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int request = 0; request < 5; request++) {
                pending.add(executor.submit(() -> assertRejected(
                        identities, "stalled-account", "198.51.100.91")));
            }
            assertThat(allCredentialChecksStarted.await(10, TimeUnit.SECONDS)).isTrue();

            clock.advance(Duration.ofMinutes(16));
            assertThatThrownBy(() -> identities.authenticate(
                            "stalled-account",
                            "wrong-password",
                            "198.51.100.91"))
                    .isInstanceOf(IdentityService.LoginRateLimitedException.class);

            releaseCredentialChecks.countDown();
            for (Future<?> result : pending) {
                result.get(10, TimeUnit.SECONDS);
            }

            assertThatThrownBy(() -> identities.authenticate(
                            "stalled-account",
                            "wrong-password",
                            "198.51.100.91"))
                    .isInstanceOf(IdentityService.LoginRateLimitedException.class);

            clock.advance(Duration.ofMinutes(16));
            assertRejected(identities, "stalled-account", "198.51.100.91");
        } finally {
            releaseCredentialChecks.countDown();
        }
    }

    @Test
    void fiftyConcurrentFailuresReserveOnlyFiveCredentialChecks() throws Exception {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        UserAccount account = UserAccount.create(
                "limited-account",
                "Limited Account",
                "stored-hash",
                Set.of(RoleName.DEVELOPER),
                false);
        when(users.findByUsername("limited-account")).thenReturn(Optional.of(account));

        CountDownLatch releaseCredentialChecks = new CountDownLatch(1);
        CountDownLatch allDecisionsObserved = new CountDownLatch(50);
        AtomicInteger credentialChecks = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();
        when(passwords.matches("wrong-password", "stored-hash")).thenAnswer(invocation -> {
            credentialChecks.incrementAndGet();
            allDecisionsObserved.countDown();
            if (!releaseCredentialChecks.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Credential checks were not released");
            }
            return false;
        });
        IdentityService identities = new IdentityService(users, passwords);
        IdentityController controller = new IdentityController(
                identities,
                mock(JwtCookieService.class),
                new ClientIpResolver(""),
                CookieCsrfTokenRepository.withHttpOnlyFalse(),
                mock(AuditService.class),
                Clock.systemUTC());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int request = 0; request < 50; request++) {
                results.add(executor.submit(() -> {
                    start.await();
                    MockHttpServletRequest servletRequest = new MockHttpServletRequest();
                    servletRequest.setRemoteAddr("198.51.100.88");
                    ResponseEntity<?> response = controller.login(
                            new IdentityController.LoginRequest(
                                    "limited-account", "wrong-password"),
                            servletRequest,
                            new MockHttpServletResponse());
                    int status = response.getStatusCode().value();
                    if (status == 429) {
                        rateLimited.incrementAndGet();
                        allDecisionsObserved.countDown();
                    }
                    return status;
                }));
            }
            start.countDown();

            try {
                assertThat(allDecisionsObserved.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(credentialChecks).hasValue(5);
                assertThat(rateLimited).hasValue(45);
            } finally {
                releaseCredentialChecks.countDown();
            }

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(10, TimeUnit.SECONDS));
            }
            assertThat(statuses).filteredOn(status -> status == 401).hasSize(5);
            assertThat(statuses).filteredOn(status -> status == 429).hasSize(45);
        }
    }

    private void rejectOrRateLimit(
            IdentityService identities, String username, String sourceIp) {
        try {
            identities.authenticate(username, "wrong-password", sourceIp);
        } catch (IdentityService.LoginRejectedException
                | IdentityService.LoginRateLimitedException ignored) {
            // Both outcomes are valid once the hard key bound is reached.
        }
    }

    private void assertRejected(
            IdentityService identities, String username, String sourceIp) {
        assertThatThrownBy(() ->
                        identities.authenticate(username, "wrong-password", sourceIp))
                .isInstanceOf(IdentityService.LoginRejectedException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
