package com.acme.opsqueue.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdentityService {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle throttle = new LoginThrottle();

    public IdentityService(UserAccountRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public CurrentUser authenticate(String username, String password, String sourceIp) {
        String normalizedUsername = UserAccount.normalizeUsername(username);
        if (throttle.blocked(normalizedUsername, sourceIp)) {
            throw new LoginRateLimitedException();
        }

        UserAccount account = users.findByUsername(normalizedUsername).orElse(null);
        if (account == null
                || !account.enabled()
                || !passwordEncoder.matches(password, account.passwordHash())) {
            throttle.failure(normalizedUsername, sourceIp);
            throw new LoginRejectedException();
        }

        throttle.success(normalizedUsername, sourceIp);
        account.recordLogin(Instant.now());
        return toCurrentUser(account);
    }

    @Transactional(readOnly = true)
    public UserAccount requireEnabled(UUID id) {
        return users.findById(id)
                .filter(UserAccount::enabled)
                .orElseThrow(LoginRejectedException::new);
    }

    @Transactional
    public void changeOwnPassword(
            UUID userId, String currentPassword, String newPassword) {
        UserAccount account = requireAccount(userId);
        if (!account.enabled()
                || !passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        account.changePassword(passwordEncoder.encode(newPassword));
    }

    @Transactional
    public UserAccount create(
            String username,
            String displayName,
            String initialPassword,
            Set<RoleName> roles) {
        String normalized = UserAccount.normalizeUsername(username);
        if (users.existsByUsername(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        UserAccount account = UserAccount.create(
                normalized,
                displayName,
                passwordEncoder.encode(initialPassword),
                roles,
                true);
        return users.save(account);
    }

    @Transactional
    public void disable(UUID userId) {
        requireAccount(userId).disable();
    }

    @Transactional
    public void resetPassword(UUID userId, String initialPassword) {
        requireAccount(userId).resetPassword(passwordEncoder.encode(initialPassword));
    }

    @Transactional
    public UserAccount replaceRoles(UUID userId, Set<RoleName> roles) {
        UserAccount account = requireAccount(userId);
        account.replaceRoles(roles);
        return account;
    }

    public CurrentUser toCurrentUser(UserAccount account) {
        return new CurrentUser(
                account.id(),
                account.username(),
                account.displayName(),
                account.roles(),
                account.mustChangePassword());
    }

    private UserAccount requireAccount(UUID id) {
        return users.findById(id)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    static final class LoginRejectedException extends RuntimeException {
    }

    static final class LoginRateLimitedException extends RuntimeException {
    }

    private static final class LoginThrottle {
        private static final int MAX_FAILURES = 5;
        private static final Duration WINDOW = Duration.ofMinutes(15);

        private final ConcurrentMap<String, Deque<Instant>> usernames =
                new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Deque<Instant>> sourceIps =
                new ConcurrentHashMap<>();

        boolean blocked(String username, String sourceIp) {
            Instant cutoff = Instant.now().minus(WINDOW);
            return countSince(usernames, username, cutoff) >= MAX_FAILURES
                    || countSince(sourceIps, sourceIp, cutoff) >= MAX_FAILURES;
        }

        void failure(String username, String sourceIp) {
            Instant now = Instant.now();
            add(usernames, username, now);
            add(sourceIps, sourceIp, now);
        }

        void success(String username, String sourceIp) {
            usernames.remove(username);
            sourceIps.remove(sourceIp);
        }

        private int countSince(
                ConcurrentMap<String, Deque<Instant>> failures,
                String key,
                Instant cutoff) {
            final int[] size = {0};
            failures.computeIfPresent(key, (ignored, attempts) -> {
                synchronized (attempts) {
                    attempts.removeIf(attempt -> attempt.isBefore(cutoff));
                    size[0] = attempts.size();
                    return attempts.isEmpty() ? null : attempts;
                }
            });
            return size[0];
        }

        private void add(
                ConcurrentMap<String, Deque<Instant>> failures,
                String key,
                Instant now) {
            failures.compute(key, (ignored, attempts) -> {
                Deque<Instant> result = attempts == null ? new ArrayDeque<>() : attempts;
                synchronized (result) {
                    result.removeIf(attempt -> attempt.isBefore(now.minus(WINDOW)));
                    result.addLast(now);
                }
                return result;
            });
        }
    }
}
