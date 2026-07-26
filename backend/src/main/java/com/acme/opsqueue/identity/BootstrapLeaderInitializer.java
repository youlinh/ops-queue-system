package com.acme.opsqueue.identity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapLeaderInitializer implements ApplicationRunner {
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_UTF8_BYTES = 72;

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String displayName;
    private final String password;

    public BootstrapLeaderInitializer(
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${BOOTSTRAP_LEADER_USERNAME:}") String username,
            @Value("${BOOTSTRAP_LEADER_DISPLAY_NAME:}") String displayName,
            @Value("${BOOTSTRAP_LEADER_PASSWORD:}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.displayName = displayName;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        users.lockEnabledLeaderGuard();
        if (users.existsByEnabledRole(RoleName.LEADER)) {
            return;
        }

        List<String> missing = new ArrayList<>();
        requireValue(username, "BOOTSTRAP_LEADER_USERNAME", missing);
        requireValue(displayName, "BOOTSTRAP_LEADER_DISPLAY_NAME", missing);
        requireValue(password, "BOOTSTRAP_LEADER_PASSWORD", missing);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "No leader exists; required bootstrap values are blank: "
                            + String.join(", ", missing));
        }
        if (password.length() < MIN_PASSWORD_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length
                        > MAX_PASSWORD_UTF8_BYTES) {
            throw new IllegalStateException(
                    "BOOTSTRAP_LEADER_PASSWORD must contain at least "
                            + MIN_PASSWORD_LENGTH
                            + " characters and at most "
                            + MAX_PASSWORD_UTF8_BYTES
                            + " UTF-8 bytes, matching the password policy "
                            + "enforced for every other account");
        }

        String normalizedUsername = UserAccount.normalizeUsername(username);
        if (users.findByUsername(normalizedUsername).isPresent()) {
            throw new IllegalStateException(
                    "No enabled leader exists and bootstrap username '"
                            + normalizedUsername
                            + "' is already assigned");
        }

        users.save(UserAccount.create(
                normalizedUsername,
                displayName,
                passwordEncoder.encode(password),
                Set.of(RoleName.LEADER),
                true));
    }

    private void requireValue(String value, String name, List<String> missing) {
        if (value == null || value.isBlank()) {
            missing.add(name);
        }
    }
}
