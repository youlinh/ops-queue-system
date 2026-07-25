package com.acme.opsqueue.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.opsqueue.OpsQueueApplication;
import com.acme.opsqueue.support.MySqlIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        classes = OpsQueueApplication.class,
        properties = {
            "BOOTSTRAP_LEADER_USERNAME=bootstrap-leader",
            "BOOTSTRAP_LEADER_DISPLAY_NAME=Bootstrap Leader",
            "BOOTSTRAP_LEADER_PASSWORD=Bootstrap-Password-1",
            "JWT_SIGNING_KEY=identity-api-test-signing-key-with-at-least-32-bytes",
            "JWT_COOKIE_SECURE=false",
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

    @BeforeEach
    void removeAccountsCreatedByPreviousTests() {
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
        when(emptyRepository.existsByRolesContaining(RoleName.LEADER)).thenReturn(false);

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
        return mvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(sourceIp);
                    return request;
                })
                .contentType(APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"wrong-password"}
                        """.formatted(username)));
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
