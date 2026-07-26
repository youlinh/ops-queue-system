package com.acme.opsqueue.roster;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.opsqueue.OpsQueueApplication;
import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import com.acme.opsqueue.support.MySqlIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = OpsQueueApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DutySummaryApiTest extends MySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserAccountRepository users;
    @Autowired private DutyRosterRepository rosters;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        truncateAuditLogs();
        rosters.deleteAll();
        users.findAll().stream()
                .filter(account ->
                        !account.username().equals("test-bootstrap-leader"))
                .forEach(users::delete);
        createUser("operator");
        createUser("ops1");
        createUser("ops2");
    }

    @Test
    void todayDutySummaryIsAuthenticatedAndNamesBothDutyOperators()
            throws Exception {
        UserAccount second = users.findByUsername("ops1").orElseThrow();
        UserAccount third = users.findByUsername("ops2").orElseThrow();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        rosters.saveAndFlush(DutyRoster.of(today, second.id(), third.id()));

        mvc.perform(get("/api/duty/today"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/duty/today").cookie(login("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dutyDate").value(today.toString()))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.secondLine.id")
                        .value(second.id().toString()))
                .andExpect(jsonPath("$.secondLine.displayName").value("ops1"))
                .andExpect(jsonPath("$.thirdLine.id")
                        .value(third.id().toString()))
                .andExpect(jsonPath("$.thirdLine.displayName").value("ops2"));
    }

    @Test
    void todayDutySummaryExplicitlyReportsMissingRoster() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

        mvc.perform(get("/api/duty/today").cookie(login("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dutyDate").value(today.toString()))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.secondLine").doesNotExist())
                .andExpect(jsonPath("$.thirdLine").doesNotExist());
    }

    private UserAccount createUser(String username) {
        return users.saveAndFlush(UserAccount.create(
                username,
                username,
                passwordEncoder.encode("Test-Password-1"),
                Set.of(RoleName.OPERATOR),
                false));
    }

    private Cookie login(String username) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"username":"%s","password":"Test-Password-1"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("OPS_SESSION");
    }
}
