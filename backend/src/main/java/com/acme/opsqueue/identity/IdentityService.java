package com.acme.opsqueue.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdentityService {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle throttle;

    @Autowired
    public IdentityService(UserAccountRepository users, PasswordEncoder passwordEncoder) {
        this(users, passwordEncoder, Clock.systemUTC());
    }

    IdentityService(
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.throttle = new LoginThrottle(clock);
    }

    @Transactional
    public CurrentUser authenticate(String username, String password, String sourceIp) {
        String normalizedUsername = UserAccount.normalizeUsername(username);
        String normalizedSourceIp = sourceIp == null ? "" : sourceIp;
        LoginThrottle.Attempt attempt =
                throttle.reserve(normalizedUsername, normalizedSourceIp);
        if (attempt == null) {
            throw new LoginRateLimitedException();
        }

        try {
            UserAccount account = users.findByUsername(normalizedUsername).orElse(null);
            if (account == null
                    || !account.enabled()
                    || !passwordEncoder.matches(password, account.passwordHash())) {
                throttle.failure(attempt);
                throw new LoginRejectedException();
            }

            throttle.success(attempt);
            account.recordLogin(Instant.now());
            return toCurrentUser(account);
        } catch (LoginRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throttle.cancel(attempt);
            throw exception;
        }
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
        afterUsernameAvailabilityCheck(normalized);
        UserAccount account = UserAccount.create(
                normalized,
                displayName,
                passwordEncoder.encode(initialPassword),
                roles,
                true);
        try {
            return users.saveAndFlush(account);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Username already exists", exception);
        }
    }

    void afterUsernameAvailabilityCheck(String normalizedUsername) {
        // Test seam for deterministically exercising the database uniqueness race.
    }

    int throttleUsernameKeyCount() {
        return throttle.usernameKeyCount();
    }

    int throttleSourceIpKeyCount() {
        return throttle.sourceIpKeyCount();
    }

    @Transactional
    public void disable(UUID userId) {
        List<UserAccount> enabledLeaders =
                lockAndFindEnabledLeaders();
        UserAccount account = requireAccount(userId);
        rejectRemovingLastLeader(account, enabledLeaders, false);
        account.disable();
    }

    @Transactional
    public void resetPassword(UUID userId, String initialPassword) {
        requireAccount(userId).resetPassword(passwordEncoder.encode(initialPassword));
    }

    @Transactional
    public UserAccount replaceRoles(UUID userId, Set<RoleName> roles) {
        List<UserAccount> enabledLeaders =
                lockAndFindEnabledLeaders();
        UserAccount account = requireAccount(userId);
        rejectRemovingLastLeader(
                account, enabledLeaders, roles.contains(RoleName.LEADER));
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

    private void rejectRemovingLastLeader(
            UserAccount account,
            List<UserAccount> enabledLeaders,
            boolean remainsLeader) {
        if (account.enabled()
                && account.roles().contains(RoleName.LEADER)
                && !remainsLeader
                && enabledLeaders.size() == 1
                && enabledLeaders.getFirst().id().equals(account.id())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "At least one enabled leader is required");
        }
    }

    private List<UserAccount> lockAndFindEnabledLeaders() {
        users.lockEnabledLeaderGuard();
        return users.findEnabledByRole(RoleName.LEADER);
    }

    static final class LoginRejectedException extends RuntimeException {
    }

    static final class LoginRateLimitedException extends RuntimeException {
    }

    private static final class LoginThrottle {
        private static final int MAX_FAILURES = 5;
        private static final Duration WINDOW = Duration.ofMinutes(15);
        private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);
        private static final int LOCK_STRIPES = 256;
        private static final int MAX_KEYS_PER_DIMENSION = 4_096;

        private final ConcurrentMap<String, Deque<Attempt>> usernames =
                new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Deque<Attempt>> sourceIps =
                new ConcurrentHashMap<>();
        private final ReentrantLock[] locks = createLocks();
        private final ReentrantLock maintenanceLock = new ReentrantLock();
        private final Clock clock;
        private final AtomicLong nextCleanupAtMillis;

        private LoginThrottle(Clock clock) {
            this.clock = clock;
            this.nextCleanupAtMillis =
                    new AtomicLong(clock.millis() + CLEANUP_INTERVAL.toMillis());
        }

        Attempt reserve(String username, String sourceIp) {
            maybeCleanup();
            return withLocks(username, sourceIp, () -> {
                Instant now = clock.instant();
                Instant cutoff = now.minus(WINDOW);
                Deque<Attempt> usernameAttempts =
                        activeAttempts(usernames, username, cutoff);
                Deque<Attempt> sourceIpAttempts =
                        activeAttempts(sourceIps, sourceIp, cutoff);
                if (usernameAttempts.size() >= MAX_FAILURES
                        || sourceIpAttempts.size() >= MAX_FAILURES
                        || (!usernames.containsKey(username)
                                && usernames.size() >= MAX_KEYS_PER_DIMENSION)
                        || (!sourceIps.containsKey(sourceIp)
                                && sourceIps.size() >= MAX_KEYS_PER_DIMENSION)) {
                    return null;
                }

                Attempt attempt = new Attempt(username, sourceIp);
                usernames.put(username, usernameAttempts);
                sourceIps.put(sourceIp, sourceIpAttempts);
                usernameAttempts.addLast(attempt);
                sourceIpAttempts.addLast(attempt);
                return attempt;
            });
        }

        void failure(Attempt attempt) {
            withLocks(attempt.username, attempt.sourceIp, () -> {
                attempt.failedAt = clock.instant();
                return null;
            });
        }

        void success(Attempt attempt) {
            withLocks(attempt.username, attempt.sourceIp, () -> {
                Deque<Attempt> usernameAttempts = usernames.get(attempt.username);
                if (usernameAttempts != null) {
                    usernameAttempts.removeIf(
                            candidate -> candidate == attempt
                                    || candidate.failedAt != null);
                    if (usernameAttempts.isEmpty()) {
                        usernames.remove(attempt.username, usernameAttempts);
                    }
                }

                Deque<Attempt> sourceIpAttempts = sourceIps.get(attempt.sourceIp);
                if (sourceIpAttempts != null) {
                    sourceIpAttempts.remove(attempt);
                    if (sourceIpAttempts.isEmpty()) {
                        sourceIps.remove(attempt.sourceIp, sourceIpAttempts);
                    }
                }
                return null;
            });
        }

        void cancel(Attempt attempt) {
            withLocks(attempt.username, attempt.sourceIp, () -> {
                removeAttempt(usernames, attempt.username, attempt);
                removeAttempt(sourceIps, attempt.sourceIp, attempt);
                return null;
            });
        }

        int usernameKeyCount() {
            return keyCount(usernames);
        }

        int sourceIpKeyCount() {
            return keyCount(sourceIps);
        }

        private Deque<Attempt> activeAttempts(
                ConcurrentMap<String, Deque<Attempt>> attemptsByKey,
                String key,
                Instant cutoff) {
            Deque<Attempt> attempts =
                    attemptsByKey.get(key);
            if (attempts == null) {
                return new ArrayDeque<>();
            }
            attempts.removeIf(attempt -> attempt.failedAt != null
                    && attempt.failedAt.isBefore(cutoff));
            if (attempts.isEmpty()) {
                attemptsByKey.remove(key, attempts);
                return new ArrayDeque<>();
            }
            return attempts;
        }

        private <T> T withLocks(String username, String sourceIp, Supplier<T> action) {
            int usernameStripe = stripe("username:" + username);
            int sourceIpStripe = stripe("source-ip:" + sourceIp);
            int firstStripe = Math.min(usernameStripe, sourceIpStripe);
            int secondStripe = Math.max(usernameStripe, sourceIpStripe);
            ReentrantLock first = locks[firstStripe];
            ReentrantLock second = locks[secondStripe];

            maintenanceLock.lock();
            try {
                first.lock();
                try {
                    if (firstStripe != secondStripe) {
                        second.lock();
                    }
                    try {
                        return action.get();
                    } finally {
                        if (firstStripe != secondStripe) {
                            second.unlock();
                        }
                    }
                } finally {
                    first.unlock();
                }
            } finally {
                maintenanceLock.unlock();
            }
        }

        private void maybeCleanup() {
            long now = clock.millis();
            long scheduled = nextCleanupAtMillis.get();
            if (now < scheduled
                    || !nextCleanupAtMillis.compareAndSet(
                            scheduled, now + CLEANUP_INTERVAL.toMillis())) {
                return;
            }

            maintenanceLock.lock();
            try {
                Instant cutoff = clock.instant().minus(WINDOW);
                expireAll(usernames, cutoff);
                expireAll(sourceIps, cutoff);
            } finally {
                maintenanceLock.unlock();
            }
        }

        private void expireAll(
                ConcurrentMap<String, Deque<Attempt>> attemptsByKey,
                Instant cutoff) {
            attemptsByKey.forEach((key, attempts) -> {
                attempts.removeIf(attempt -> attempt.failedAt != null
                        && attempt.failedAt.isBefore(cutoff));
                if (attempts.isEmpty()) {
                    attemptsByKey.remove(key, attempts);
                }
            });
        }

        private void removeAttempt(
                ConcurrentMap<String, Deque<Attempt>> attemptsByKey,
                String key,
                Attempt attempt) {
            Deque<Attempt> attempts = attemptsByKey.get(key);
            if (attempts == null) {
                return;
            }
            attempts.remove(attempt);
            if (attempts.isEmpty()) {
                attemptsByKey.remove(key, attempts);
            }
        }

        private int keyCount(ConcurrentMap<String, Deque<Attempt>> attemptsByKey) {
            maintenanceLock.lock();
            try {
                return attemptsByKey.size();
            } finally {
                maintenanceLock.unlock();
            }
        }

        private int stripe(String key) {
            return Math.floorMod(key.hashCode(), locks.length);
        }

        private static ReentrantLock[] createLocks() {
            ReentrantLock[] result = new ReentrantLock[LOCK_STRIPES];
            for (int index = 0; index < result.length; index++) {
                result[index] = new ReentrantLock();
            }
            return result;
        }

        static final class Attempt {
            private final String username;
            private final String sourceIp;
            private Instant failedAt;

            private Attempt(String username, String sourceIp) {
                this.username = username;
                this.sourceIp = sourceIp;
            }
        }
    }
}
