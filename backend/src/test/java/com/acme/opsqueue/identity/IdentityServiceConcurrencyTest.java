package com.acme.opsqueue.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

class IdentityServiceConcurrencyTest {
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
                CookieCsrfTokenRepository.withHttpOnlyFalse());
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
}
