package com.acme.opsqueue.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.opsqueue.OpsQueueApplication;
import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import com.acme.opsqueue.support.MySqlIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = OpsQueueApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RosterApiTest extends MySqlIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private RosterImportBatchRepository batches;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE audit_logs");
        batches.deleteAll();
        users.deleteAll();
        createUser("leader", Set.of(RoleName.LEADER));
        createUser("operator", Set.of(RoleName.OPERATOR));
        createUser("ops1", Set.of(RoleName.OPERATOR));
        createUser("ops2", Set.of(RoleName.OPERATOR));
    }

    @Test
    void rosterManagementEndpointsAreLeaderOnly() throws Exception {
        Cookie operator = login("operator");

        mvc.perform(get("/api/rosters/template").cookie(operator))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/rosters").cookie(operator))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/rosters/imports").cookie(operator))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/rosters/imports/{batchId}", java.util.UUID.randomUUID()).cookie(operator))
                .andExpect(status().isForbidden());
    }

    @Test
    void leaderPreviewsThenConfirmsAndSecondConfirmationIsConflict() throws Exception {
        Cookie leader = login("leader");
        MockMultipartFile file = new MockMultipartFile(
                "file", "roster.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                RosterWorkbookFixture.workbook(List.<String[]>of(
                        new String[] {"2026-07-25", "ops1", "ops2"})));

        MvcResult preview = mvc.perform(multipart("/api/rosters/imports/preview")
                        .file(file).with(csrf()).cookie(leader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.batchId").isNotEmpty())
                .andReturn();
        String batchId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(preview.getResponse().getContentAsString()).path("batchId").asText();

        mvc.perform(post("/api/rosters/imports/{batchId}/confirm", batchId)
                        .with(csrf()).cookie(leader))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/rosters/imports/{batchId}/confirm", batchId)
                        .with(csrf()).cookie(leader))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/rosters").cookie(leader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dutyDate").value("2026-07-25"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'ROSTER_CONFIRMED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void previewUsesStable413ForUploadLimitsAnd422ForInvalidWorkbookShape() throws Exception {
        Cookie leader = login("leader");
        MvcResult limit = mvc.perform(multipart("/api/rosters/imports/preview").file(new MockMultipartFile(
                        "file", "large.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[1_000_001])).with(csrf()).cookie(leader))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.batchId").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].message").value("上传文件超过安全限制"))
                .andReturn();
        String limitBatchId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(limit.getResponse().getContentAsString()).path("batchId").asText();
        mvc.perform(get("/api/rosters/imports/{batchId}", limitBatchId).cookie(leader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errors[0].message").value("上传文件超过安全限制"));
        mvc.perform(multipart("/api/rosters/imports/preview").file(new MockMultipartFile(
                        "file", "invalid.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        RosterWorkbookFixture.headerOnlyOrExtraColumn(true))).with(csrf()).cookie(leader))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.batchId").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].rowNumber").value(1))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    private UserAccount createUser(String username, Set<RoleName> roles) {
        return users.save(UserAccount.create(
                username,
                username,
                passwordEncoder.encode("Test-Password-1"),
                roles,
                false));
    }

    private Cookie login(String username) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"%s\",\"password\":\"Test-Password-1\"}".formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("OPS_SESSION");
    }
}
