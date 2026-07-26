package com.acme.opsqueue.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.opsqueue.OpsQueueApplication;
import com.acme.opsqueue.support.MySqlIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.springframework.dao.OptimisticLockingFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        classes = OpsQueueApplication.class,
        properties = {
            "BOOTSTRAP_LEADER_USERNAME=bootstrap-leader",
            "BOOTSTRAP_LEADER_DISPLAY_NAME=Bootstrap Leader",
            "BOOTSTRAP_LEADER_PASSWORD=Bootstrap-Password-1",
            "JWT_SIGNING_KEY=identity-api-test-signing-key-with-at-least-32-bytes",
            "JWT_COOKIE_SECURE=false",
            "TRUSTED_PROXY_CIDRS=10.99.0.0/16",
            "spring.security.user.name=framework-user",
            "spring.security.user.password=$2a$10$dnDhU1DIBE6pn8SxwQHVfO3VgQ0rjyuiZpTGKpl.rj2dl2yLJ6h.q"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdentityApiTest extends MySqlIntegrationTest {
    private static final String BOOTSTRAP_USERNAME = "bootstrap-leader";
    private static final String BOOTSTRAP_PASSWORD = "Bootstrap-Password-1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private IdentityService identities;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private CookieCsrfTokenRepository csrfTokenRepository;

    @BeforeEach
    void removeAccountsCreatedByPreviousTests() {
        jdbc.update("DELETE FROM audit_logs");
        springSecurityFilterChain.getFilters("/api/auth/csrf").stream()
                .filter(CsrfFilter.class::isInstance)
                .map(CsrfFilter.class::cast)
                .forEach(filter -> ReflectionTestUtils.setField(
                        filter, "tokenRepository", csrfTokenRepository));
        users.findAll().stream()
                .filter(user -> !BOOTSTRAP_USERNAME.equals(user.username()))
                .forEach(users::delete);
        UserAccount leader = users.findByUsername(BOOTSTRAP_USERNAME).orElseThrow();
        leader.resetPassword(passwordEncoder.encode(BOOTSTRAP_PASSWORD));
        users.saveAndFlush(leader);
    }

    @Test
    void bootstrapCreatesFirstLeaderWithBcryptInitialPassword() {
        UserAccount leader = users.findByUsername(BOOTSTRAP_USERNAME).orElseThrow();

        assertThat(leader.enabled()).isTrue();
        assertThat(leader.mustChangePassword()).isTrue();
        assertThat(leader.roles()).containsExactly(RoleName.LEADER);
        assertThat(passwordEncoder.matches(BOOTSTRAP_PASSWORD, leader.passwordHash())).isTrue();
        assertThat(leader.passwordHash()).startsWith("$2");
    }

    @Test
    void initialPasswordLoginRequiresChangeAndUsesStrictHttpOnlyCookie() throws Exception {
        createUser("dev1", "Initial-Password-1", Set.of(RoleName.DEVELOPER), true);

        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"dev1","password":"Initial-Password-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dev1"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(cookie().httpOnly("OPS_SESSION", true))
                .andReturn();

        Cookie session = result.getResponse().getCookie("OPS_SESSION");
        assertThat(session).isNotNull();
        assertThat(session.getAttribute("SameSite")).isEqualTo("Strict");
    }

    @Test
    void invalidLoginRequestDoesNotUseTaskValidationErrorContract() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));
    }

    @Test
    void forcedPasswordChangeBlocksBusinessApisUntilPasswordIsChanged() throws Exception {
        createUser("dev2", "Initial-Password-2", Set.of(RoleName.DEVELOPER), true);
        Cookie session = login("dev2", "Initial-Password-2", "10.20.0.2");

        mvc.perform(get("/api/business-probe").cookie(session))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/change-password")
                        .with(csrf())
                        .cookie(session)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Initial-Password-2",
                                 "newPassword":"Changed-Password-2"}
                                """))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/business-probe").cookie(session))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"dev2","password":"Initial-Password-2"}
                                """))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"dev2","password":"Changed-Password-2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void authenticatedMutationsRequireCsrfToken() throws Exception {
        Cookie leader = readyBootstrapLeaderCookie();

        mvc.perform(post("/api/admin/users")
                        .cookie(leader)
                        .contentType(APPLICATION_JSON)
                        .content(createUserJson("csrf-user", "DEVELOPER")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .cookie(leader)
                        .contentType(APPLICATION_JSON)
                        .content(createUserJson("csrf-user", "DEVELOPER")))
                .andExpect(status().isCreated());
    }

    @Test
    void csrfBootstrapSetsReadableCookieAndReturnsMatchingToken() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        Cookie csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(result.getResponse().getContentAsString()).contains(csrfCookie.getValue());
    }

    @Test
    void browserCookieAndHeaderCsrfRoundTripAuthorizesMutation() throws Exception {
        Cookie leader = readyBootstrapLeaderCookie();
        MvcResult bootstrap = mvc.perform(get("/api/auth/csrf").cookie(leader))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrfCookie = bootstrap.getResponse().getCookie("XSRF-TOKEN");
        String token = jsonValue(bootstrap, "token");

        mvc.perform(post("/api/admin/users")
                        .cookie(leader, csrfCookie)
                        .header("X-XSRF-TOKEN", token)
                        .contentType(APPLICATION_JSON)
                        .content(createUserJson("browser-csrf-user", "DEVELOPER")))
                .andExpect(status().isCreated());
    }

    @Test
    void passwordsHonorBcryptUtf8ByteBoundary() throws Exception {
        Cookie leader = readyBootstrapLeaderCookie();

        createAccountWithPassword(leader, "ascii-72", "A".repeat(72))
                .andExpect(status().isCreated());
        createAccountWithPassword(leader, "ascii-73", "A".repeat(73))
                .andExpect(status().isBadRequest());
        createAccountWithPassword(leader, "utf8-72", "界".repeat(24))
                .andExpect(status().isCreated());
        createAccountWithPassword(leader, "utf8-75", "界".repeat(25))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nullRoleElementsAreRejectedAsBadRequests() throws Exception {
        Cookie leader = readyBootstrapLeaderCookie();
        UUID leaderId = users.findByUsername(BOOTSTRAP_USERNAME).orElseThrow().id();

        mvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .cookie(leader)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"null-role","displayName":"Null Role",
                                 "initialPassword":"Initial-Password-9",
                                 "roles":[null]}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/admin/users/{id}/roles", leaderId)
                        .with(csrf())
                        .cookie(leader)
                        .contentType(APPLICATION_JSON)
                        .content("{\"roles\":[null]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/admin/users/{id}/roles", leaderId)
                        .with(csrf())
                        .cookie(leader)
                        .contentType(APPLICATION_JSON)
                        .content("{\"roles\":[\"NOT_A_ROLE\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentSameUsernameCreationReturnsOneConflict() throws Exception {
        String username = "duplicate-" + UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier bothObservedAbsence = new CyclicBarrier(2);
        AtomicInteger availabilityChecks = new AtomicInteger();
        IdentityService racingIdentities = new IdentityService(users, passwordEncoder) {
            @Override
            void afterUsernameAvailabilityCheck(String normalizedUsername) {
                assertThat(normalizedUsername).isEqualTo(username);
                availabilityChecks.incrementAndGet();
                try {
                    bothObservedAbsence.await(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(
                            "Both creates did not pass the username precheck", exception);
                }
            }
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(
                    () -> createAfterSignal(racingIdentities, username, start));
            Future<String> second = executor.submit(
                    () -> createAfterSignal(racingIdentities, username, start));
            start.countDown();

            assertThat(Stream.of(first.get(10, TimeUnit.SECONDS),
                            second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", "CONFLICT");
        }
        assertThat(availabilityChecks).hasValue(2);
        assertThat(users.findAll().stream()
                .filter(account -> account.username().equals(username)))
                .hasSize(1);
    }

    @Test
    void optimisticVersionPreventsConcurrentPasswordStateOverwrite()
            throws Exception {
        UserAccount target = createUser(
                "optimistic-target",
                "Original-Password-1",
                Set.of(RoleName.DEVELOPER),
                false);
        String changedHash = passwordEncoder.encode("Changed-Password-1");
        String resetHash = passwordEncoder.encode("Reset-Password-1");
        assertThat(raceMutations(
                target.id(),
                account -> account.changePassword(changedHash),
                account -> account.resetPassword(resetHash)))
                .containsExactlyInAnyOrder("UPDATED", "CONFLICT");

        UserAccount persisted = users.findById(target.id()).orElseThrow();
        boolean changedWon = passwordEncoder.matches(
                "Changed-Password-1", persisted.passwordHash());
        boolean resetWon = passwordEncoder.matches(
                "Reset-Password-1", persisted.passwordHash());
        assertThat(changedWon).isNotEqualTo(resetWon);
        assertThat(persisted.mustChangePassword()).isEqualTo(resetWon);
    }

    @Test
    void optimisticVersionPreventsConcurrentDisableFromBeingPartiallyOverwritten()
            throws Exception {
        UserAccount target = createUser(
                "optimistic-disable",
                "Original-Password-1",
                Set.of(RoleName.DEVELOPER),
                false);
        String changedHash = passwordEncoder.encode("Changed-Password-1");

        assertThat(raceMutations(
                target.id(),
                account -> account.changePassword(changedHash),
                UserAccount::disable))
                .containsExactlyInAnyOrder("UPDATED", "CONFLICT");

        UserAccount persisted = users.findById(target.id()).orElseThrow();
        boolean passwordChanged = passwordEncoder.matches(
                "Changed-Password-1", persisted.passwordHash());
        assertThat(passwordChanged).isNotEqualTo(!persisted.enabled());
    }

    @Test
    void optimisticVersionPreventsConcurrentRoleChangeFromBeingPartiallyOverwritten()
            throws Exception {
        UserAccount target = createUser(
                "optimistic-roles",
                "Original-Password-1",
                Set.of(RoleName.DEVELOPER),
                false);
        String changedHash = passwordEncoder.encode("Changed-Password-1");

        assertThat(raceMutations(
                target.id(),
                account -> account.changePassword(changedHash),
                account -> account.replaceRoles(Set.of(RoleName.OPERATOR))))
                .containsExactlyInAnyOrder("UPDATED", "CONFLICT");

        UserAccount persisted = users.findById(target.id()).orElseThrow();
        boolean passwordChanged = passwordEncoder.matches(
                "Changed-Password-1", persisted.passwordHash());
        boolean rolesChanged = persisted.roles().equals(Set.of(RoleName.OPERATOR));
        assertThat(passwordChanged).isNotEqualTo(rolesChanged);
    }

    @Test
    void nonLeaderCannotCreateAccounts() throws Exception {
        createUser("dev3", "Initial-Password-3", Set.of(RoleName.DEVELOPER), false);
        Cookie developer = login("dev3", "Initial-Password-3", "10.20.0.3");

        mvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .cookie(developer)
                        .contentType(APPLICATION_JSON)
                        .content(createUserJson("ops1", "OPERATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void lastEnabledLeaderCannotDisableSelf() throws Exception {
        Cookie leaderCookie = readyBootstrapLeaderCookie();
        UUID leaderId = users.findByUsername(BOOTSTRAP_USERNAME).orElseThrow().id();

        try {
            mvc.perform(post("/api/admin/users/{id}/disable", leaderId)
                            .with(csrf())
                            .cookie(leaderCookie))
                    .andExpect(status().isConflict());
            assertThat(users.findById(leaderId).orElseThrow().enabled()).isTrue();
        } finally {
            jdbc.update(
                    "UPDATE users SET enabled = TRUE WHERE username = ?",
                    BOOTSTRAP_USERNAME);
        }
    }

    @Test
    void lastEnabledLeaderCannotRemoveOwnLeaderRole() throws Exception {
        Cookie leaderCookie = readyBootstrapLeaderCookie();
        UUID leaderId = users.findByUsername(BOOTSTRAP_USERNAME).orElseThrow().id();

        try {
            mvc.perform(put("/api/admin/users/{id}/roles", leaderId)
                            .with(csrf())
                            .cookie(leaderCookie)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"roles":["DEVELOPER"]}
                                    """))
                    .andExpect(status().isConflict());
            assertThat(users.findById(leaderId).orElseThrow().roles())
                    .contains(RoleName.LEADER);
        } finally {
            jdbc.update(
                    "INSERT IGNORE INTO user_roles (user_id, role_name) "
                            + "SELECT id, 'LEADER' FROM users WHERE username = ?",
                    BOOTSTRAP_USERNAME);
        }
    }

    @Test
    void concurrentOperationsCannotRemoveAllEnabledLeaders() throws Exception {
        UserAccount second = createUser(
                "concurrent-leader",
                "Concurrent-Leader-Password-1",
                Set.of(RoleName.LEADER),
                false);
        UUID bootstrapId = users.findByUsername(BOOTSTRAP_USERNAME).orElseThrow().id();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(
                    () -> disableAfterSignal(bootstrapId, start));
            Future<String> secondResult = executor.submit(
                    () -> disableAfterSignal(second.id(), start));
            start.countDown();

            assertThat(Stream.of(first.get(10, TimeUnit.SECONDS),
                            secondResult.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("DISABLED", "CONFLICT");
            assertThat(jdbc.queryForObject(
                    """
                            SELECT COUNT(*)
                            FROM users u
                            JOIN user_roles ur ON ur.user_id = u.id
                            WHERE u.enabled = TRUE AND ur.role_name = 'LEADER'
                            """,
                    Integer.class)).isEqualTo(1);
        } finally {
            jdbc.update(
                    "UPDATE users SET enabled = TRUE WHERE username = ?",
                    BOOTSTRAP_USERNAME);
        }
    }

    @Test
    void bootstrapFailsClosedWhenConfiguredUsernameBelongsToDisabledLeader() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        UserAccount collision = UserAccount.create(
                BOOTSTRAP_USERNAME,
                "Disabled Bootstrap",
                passwordEncoder.encode("Disabled-Bootstrap-Password-1"),
                Set.of(RoleName.LEADER),
                true);
        collision.disable();
        when(repository.findByUsername(BOOTSTRAP_USERNAME)).thenReturn(
                java.util.Optional.of(collision));
        BootstrapLeaderInitializer initializer = new BootstrapLeaderInitializer(
                repository,
                passwordEncoder,
                BOOTSTRAP_USERNAME,
                "Bootstrap Leader",
                BOOTSTRAP_PASSWORD);

        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BOOTSTRAP_USERNAME)
                .hasMessageContaining("enabled");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void bootstrapFailsClosedWhenConfiguredUsernameBelongsToNonLeader() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        UserAccount collision = UserAccount.create(
                BOOTSTRAP_USERNAME,
                "Existing Developer",
                passwordEncoder.encode("Existing-Developer-Password-1"),
                Set.of(RoleName.DEVELOPER),
                true);
        when(repository.findByUsername(BOOTSTRAP_USERNAME)).thenReturn(
                java.util.Optional.of(collision));
        BootstrapLeaderInitializer initializer = new BootstrapLeaderInitializer(
                repository,
                passwordEncoder,
                BOOTSTRAP_USERNAME,
                "Bootstrap Leader",
                BOOTSTRAP_PASSWORD);

        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BOOTSTRAP_USERNAME)
                .hasMessageContaining("enabled");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void trustedProxySeparatesForwardedClientIpBuckets() throws Exception {
        String proxyIp = "10.99.0.2";
        for (int attempt = 0; attempt < 5; attempt++) {
            failedLogin(
                            "proxy-target-" + UUID.randomUUID(),
                            proxyIp,
                            "203.0.113.10")
                    .andExpect(status().isUnauthorized());
        }

        failedLogin(
                        "different-client-" + UUID.randomUUID(),
                        proxyIp,
                        "203.0.113.11")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void directClientCannotSpoofForwardedAddressToEvadeIpThrottle() throws Exception {
        String directIp = "198.51.100.220";
        for (int attempt = 0; attempt < 5; attempt++) {
            failedLogin(
                            "direct-target-" + UUID.randomUUID(),
                            directIp,
                            "192.0.2." + attempt)
                    .andExpect(status().isUnauthorized());
        }

        failedLogin(
                        "direct-target-" + UUID.randomUUID(),
                        directIp,
                        "192.0.2.99")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void successfulLoginDoesNotEraseOtherUsernameFailuresFromIpBucket()
            throws Exception {
        String sourceIp = "198.51.100.221";
        createUser(
                "owned-account",
                "Owned-Account-Password-1",
                Set.of(RoleName.DEVELOPER),
                false);
        for (int attempt = 0; attempt < 4; attempt++) {
            failedLogin("spray-" + UUID.randomUUID(), sourceIp)
                    .andExpect(status().isUnauthorized());
        }

        login("owned-account", "Owned-Account-Password-1", sourceIp);
        failedLogin("spray-" + UUID.randomUUID(), sourceIp)
                .andExpect(status().isUnauthorized());
        failedLogin("spray-" + UUID.randomUUID(), sourceIp)
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void frameworkBasicAuthenticationCannotBypassLocalAccounts() throws Exception {
        mvc.perform(get("/api/business-probe")
                        .with(httpBasic("framework-user", "Framework-Password-1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anyLeaderCanCreateDisableResetAndChangeRoles() throws Exception {
        Cookie firstLeader = readyBootstrapLeaderCookie();
        MvcResult secondLeaderResult = mvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .cookie(firstLeader)
                        .contentType(APPLICATION_JSON)
                        .content(createUserJson("second-leader", "LEADER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles[0]").value("LEADER"))
                .andReturn();
        UUID secondLeaderId = UUID.fromString(
                jsonValue(secondLeaderResult, "id"));

        UserAccount secondLeader = users.findById(secondLeaderId).orElseThrow();
        secondLeader.changePassword(passwordEncoder.encode("Second-Leader-Password-1"));
        users.saveAndFlush(secondLeader);
        Cookie secondLeaderCookie =
                login("second-leader", "Second-Leader-Password-1", "10.20.0.4");

        MvcResult targetResult = mvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .cookie(secondLeaderCookie)
                        .contentType(APPLICATION_JSON)
                        .content(createUserJson("managed-user", "DEVELOPER")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID targetId = UUID.fromString(jsonValue(targetResult, "id"));

        mvc.perform(put("/api/admin/users/{id}/roles", targetId)
                        .with(csrf())
                        .cookie(secondLeaderCookie)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"roles":["DEVELOPER","OPERATOR"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2));

        mvc.perform(post("/api/admin/users/{id}/reset-password", targetId)
                        .with(csrf())
                        .cookie(secondLeaderCookie)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"initialPassword":"Managed-Reset-Password-1"}
                                """))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/admin/users/{id}/disable", targetId)
                        .with(csrf())
                        .cookie(secondLeaderCookie))
                .andExpect(status().isNoContent());

        UserAccount managed = users.findById(targetId).orElseThrow();
        assertThat(managed.enabled()).isFalse();
        assertThat(managed.mustChangePassword()).isTrue();
        assertThat(managed.roles()).containsExactlyInAnyOrder(
                RoleName.DEVELOPER, RoleName.OPERATOR);
        assertThat(passwordEncoder.matches(
                "Managed-Reset-Password-1", managed.passwordHash())).isTrue();
    }

    @Test
    void disablingAccountInvalidatesItsExistingCookieOnNextRequest() throws Exception {
        UserAccount target = createUser(
                "disable-target",
                "Disable-Password-1",
                Set.of(RoleName.DEVELOPER),
                false);
        Cookie targetCookie =
                login("disable-target", "Disable-Password-1", "10.20.0.5");
        Cookie leader = readyBootstrapLeaderCookie();

        mvc.perform(get("/api/auth/me").cookie(targetCookie))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/users/{id}/disable", target.id())
                        .with(csrf())
                        .cookie(leader))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(targetCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleChangesTakeEffectForExistingCookie() throws Exception {
        UserAccount target = createUser(
                "promote-target",
                "Promote-Password-1",
                Set.of(RoleName.DEVELOPER),
                false);
        Cookie targetCookie =
                login("promote-target", "Promote-Password-1", "10.20.0.6");
        Cookie leader = readyBootstrapLeaderCookie();

        mvc.perform(put("/api/admin/users/{id}/roles", target.id())
                        .with(csrf())
                        .cookie(leader)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"roles":["DEVELOPER","LEADER"]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .cookie(targetCookie)
                        .contentType(APPLICATION_JSON)
                        .content(createUserJson("created-after-promotion", "OPERATOR")))
                .andExpect(status().isCreated());
    }

    @Test
    void accountAdministrationOffersNoPhysicalDelete() throws Exception {
        UserAccount target = createUser(
                "no-delete-target",
                "No-Delete-Password-1",
                Set.of(RoleName.DEVELOPER),
                false);
        Cookie leader = readyBootstrapLeaderCookie();

        mvc.perform(delete("/api/admin/users/{id}", target.id())
                        .with(csrf())
                        .cookie(leader))
                .andExpect(status().isNotFound());

        assertThat(users.findById(target.id())).isPresent();
    }

    @Test
    void sixthFailureForSameUsernameIsRateLimitedAcrossSourceIps() throws Exception {
        String username = "limited-user-" + UUID.randomUUID();
        for (int attempt = 1; attempt <= 5; attempt++) {
            failedLogin(username, "192.0.2." + attempt)
                    .andExpect(status().isUnauthorized());
        }

        failedLogin(username, "192.0.2.99")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void sixthFailureFromSameSourceIpIsRateLimitedAcrossUsernames() throws Exception {
        String sourceIp = "198.51.100." + Math.floorMod(UUID.randomUUID().hashCode(), 200);
        for (int attempt = 1; attempt <= 5; attempt++) {
            failedLogin("unknown-" + UUID.randomUUID(), sourceIp)
                    .andExpect(status().isUnauthorized());
        }

        failedLogin("unknown-" + UUID.randomUUID(), sourceIp)
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void startupRejectsMissingBootstrapValuesWhenNoLeaderExists() {
        UserAccountRepository emptyRepository = mock(UserAccountRepository.class);

        BootstrapLeaderInitializer initializer = new BootstrapLeaderInitializer(
                emptyRepository, passwordEncoder, "", "Leader", "");

        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOTSTRAP_LEADER_USERNAME")
                .hasMessageContaining("BOOTSTRAP_LEADER_PASSWORD");
    }

    private UserAccount createUser(
            String username,
            String password,
            Set<RoleName> roles,
            boolean mustChangePassword) {
        return users.saveAndFlush(UserAccount.create(
                username,
                username,
                passwordEncoder.encode(password),
                roles,
                mustChangePassword));
    }

    private Cookie readyBootstrapLeaderCookie() throws Exception {
        UserAccount leader = users.findByUsername(BOOTSTRAP_USERNAME).orElseThrow();
        if (leader.mustChangePassword()) {
            leader.changePassword(passwordEncoder.encode(BOOTSTRAP_PASSWORD));
            users.saveAndFlush(leader);
        }
        return login(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD, "10.20.0.1");
    }

    private Cookie login(String username, String password, String sourceIp) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(sourceIp);
                            return request;
                        })
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("OPS_SESSION");
    }

    private org.springframework.test.web.servlet.ResultActions failedLogin(
            String username, String sourceIp) throws Exception {
        return failedLogin(username, sourceIp, null);
    }

    private org.springframework.test.web.servlet.ResultActions failedLogin(
            String username, String sourceIp, String forwardedFor) throws Exception {
        var request = post("/api/auth/login")
                .with(peerRequest -> {
                    peerRequest.setRemoteAddr(sourceIp);
                    return peerRequest;
                })
                .contentType(APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"wrong-password"}
                        """.formatted(username));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions createAccountWithPassword(
            Cookie leader, String username, String password) throws Exception {
        return mvc.perform(post("/api/admin/users")
                .with(csrf())
                .cookie(leader)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"username":"%s","displayName":"Password Boundary",
                         "initialPassword":"%s","roles":["DEVELOPER"]}
                        """.formatted(username, password)));
    }

    private String createAfterSignal(
            IdentityService service, String username, CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    service.create(
                            username,
                            "Concurrent Duplicate",
                            "Concurrent-Password-1",
                            Set.of(RoleName.DEVELOPER)));
            return "CREATED";
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().equals(HttpStatus.CONFLICT)) {
                return "CONFLICT";
            }
            throw exception;
        }
    }

    private String mutateConcurrently(
            UUID userId,
            CountDownLatch bothLoaded,
            java.util.function.Consumer<UserAccount> mutation) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                UserAccount account = users.findById(userId).orElseThrow();
                bothLoaded.countDown();
                try {
                    if (!bothLoaded.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent mutation did not start");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                mutation.accept(account);
                users.flush();
            });
            return "UPDATED";
        } catch (OptimisticLockingFailureException exception) {
            return "CONFLICT";
        }
    }

    private List<String> raceMutations(
            UUID userId,
            java.util.function.Consumer<UserAccount> firstMutation,
            java.util.function.Consumer<UserAccount> secondMutation)
            throws Exception {
        CountDownLatch bothLoaded = new CountDownLatch(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> mutateConcurrently(
                    userId, bothLoaded, firstMutation));
            Future<String> second = executor.submit(() -> mutateConcurrently(
                    userId, bothLoaded, secondMutation));
            return List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
        }
    }

    private String disableAfterSignal(UUID userId, CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            identities.disable(userId);
            return "DISABLED";
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().equals(HttpStatus.CONFLICT)) {
                return "CONFLICT";
            }
            throw exception;
        }
    }

    private String createUserJson(String username, String role) {
        return """
                {"username":"%s","displayName":"%s",
                 "initialPassword":"Initial-Password-9",
                 "roles":["%s"]}
                """.formatted(username, username, role);
    }

    private String jsonValue(MvcResult result, String field) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .path(field)
                .asText();
    }
}
