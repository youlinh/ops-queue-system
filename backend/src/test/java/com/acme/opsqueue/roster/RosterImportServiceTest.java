package com.acme.opsqueue.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.opsqueue.OpsQueueApplication;
import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import com.acme.opsqueue.support.MySqlIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = OpsQueueApplication.class)
@ActiveProfiles("test")
class RosterImportServiceTest extends MySqlIntegrationTest {
    @Autowired
    private RosterImportService service;

    @Autowired
    private DutyRosterRepository rosters;

    @Autowired
    private RosterImportBatchRepository batches;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID leaderId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_logs");
        rosters.deleteAll();
        batches.deleteAll();
        users.deleteAll();
        leaderId = createUser("leader", Set.of(RoleName.LEADER)).id();
        createUser("ops1", Set.of(RoleName.OPERATOR));
        createUser("ops2", Set.of(RoleName.OPERATOR));
        createUser("ops3", Set.of(RoleName.OPERATOR));
        UserAccount disabled = createUser("disabled-ops", Set.of(RoleName.OPERATOR));
        disabled.disable();
        users.saveAndFlush(disabled);
    }

    @Test
    void previewReturnsExactRowErrorsAndDoesNotStageAnInvalidWorkbook() {
        RosterImportPreview preview = service.preview("roster.xlsx", workbook(List.<String[]>of(
                new String[] {"2026-07-25", "ops1", "ops1"},
                new String[] {"2026-07-25", "ops1", "ops2"},
                new String[] {"", "ops1", "ops2"},
                new String[] {"2026-07-28", "unknown", "ops2"},
                new String[] {"2026-07-29", "disabled-ops", "ops2"},
                new String[] {"2026-07-30", "leader", "ops2"})), leaderId);

        assertThat(preview.valid()).isFalse();
        assertThat(preview.batchId()).isNotNull();
        assertThat(preview.errors()).containsExactly(
                new RosterImportError(2, "二线管理员账号和三线管理员账号不能相同"),
                new RosterImportError(3, "值班日期在文件中重复"),
                new RosterImportError(4, "值班日期不能为空或格式无效"),
                new RosterImportError(5, "二线管理员账号不存在或已停用"),
                new RosterImportError(6, "二线管理员账号不存在或已停用"),
                new RosterImportError(7, "二线管理员账号不具有运维管理员角色"));
        assertThat(batches.count()).isEqualTo(1);
        RosterImportBatch failed = batches.findByIdWithErrors(preview.batchId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(RosterImportBatch.Status.FAILED);
        assertThat(failed.rowCount()).isEqualTo(6);
        assertThat(failed.coveredDates()).isEqualTo("2026-07-25,2026-07-28,2026-07-29,2026-07-30");
        assertThat(failed.errors()).extracting(RosterImportErrorRow::sourceRowNumber)
                .containsExactly(2, 3, 4, 5, 6, 7);
        assertThat(service.history(org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                .singleElement().satisfies(view -> {
                    assertThat(view.status()).isEqualTo(RosterImportBatch.Status.FAILED);
                    assertThat(view.rowCount()).isEqualTo(6);
                    assertThat(view.errorCount()).isEqualTo(6);
                });
        RosterImportBatchDetailView detail = service.importDetail(preview.batchId());
        assertThat(detail.coveredDates()).isEqualTo(failed.coveredDates());
        assertThat(detail.errors()).hasSize(6);
    }

    @Test
    void previewStagesValidatedRowsWithAFileDigest() {
        RosterImportPreview preview = service.preview("roster.xlsx", workbook(List.<String[]>of(
                new String[] {"2026-07-25", "ops1", "ops2"},
                new String[] {"2026-07-26", "ops2", "ops3"})), leaderId);

        assertThat(preview.valid()).isTrue();
        assertThat(preview.batchId()).isNotNull();
        RosterImportBatch batch = batches.findByIdWithRows(preview.batchId()).orElseThrow();
        assertThat(batch.status()).isEqualTo(RosterImportBatch.Status.VALIDATED);
        assertThat(batch.fileSha256()).matches("[0-9a-f]{64}");
        assertThat(batch.rows()).hasSize(2);
        assertThat(batch.rows()).extracting(RosterImportRow::dutyDate)
                .containsExactly(LocalDate.parse("2026-07-25"), LocalDate.parse("2026-07-26"));
    }

    @Test
    void storesCoveredDatesBeyondTheOldVarcharBoundary() {
        List<String[]> rows = java.util.stream.IntStream.range(0, 373)
                .mapToObj(offset -> new String[] {LocalDate.of(2026, 1, 1).plusDays(offset).toString(), "ops1", "ops2"})
                .toList();

        RosterImportPreview preview = service.preview("roster.xlsx", workbook(rows), leaderId);

        assertThat(preview.valid()).isTrue();
        String dates = batches.findById(preview.batchId()).orElseThrow().coveredDates();
        assertThat(dates.split(",")).hasSize(373);
        assertThat(dates).startsWith("2026-01-01").endsWith("2027-01-08");
    }

    @Test
    void databaseCheckRejectsAnInvalidBatchStatus() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO roster_import_batches
                    (id, status, original_filename, file_sha256, row_count, uploaded_by_user_id, created_at, covered_dates)
                VALUES (UUID_TO_BIN(?), 'INVALID', 'roster.xlsx', RPAD('', 64, '0'), 0, UUID_TO_BIN(?), NOW(6), '')
                """, UUID.randomUUID().toString(), leaderId.toString()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Check constraint");
    }

    @Test
    void historyUsesTheSummaryProjectionWithoutLoadingLargeCoveredDates() {
        RosterImportPreview preview = service.preview("roster.xlsx", workbook(List.<String[]>of(
                new String[] {"2026-07-25", "ops1", "ops2"})), leaderId);
        jdbc.update("UPDATE roster_import_batches SET covered_dates = RPAD('', 200000, 'x') WHERE id = UUID_TO_BIN(?)",
                preview.batchId().toString());

        SqlCaptureInspector.reset();
        RosterImportBatchView summary = service.history(org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().getFirst();

        assertThat(summary.id()).isEqualTo(preview.batchId());
        assertThat(summary.errorCount()).isZero();
        assertThat(RosterImportBatchView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("coveredDates", "errors");
        String historySql = SqlCaptureInspector.statements().stream()
                .filter(sql -> sql.toLowerCase(java.util.Locale.ROOT).contains("roster_import_batches")
                        && sql.toLowerCase(java.util.Locale.ROOT).contains("group by"))
                .findFirst().orElseThrow();
        assertThat(historySql.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("covered_dates", " message", "source_row_number");
    }

    @Test
    void previewAcceptsRealExcelNumericDateAndRejectsDatabaseDateUnderflow() {
        assertThat(service.preview("roster.xlsx", RosterWorkbookFixture.workbookWithNumericDate(
                LocalDate.of(2026, 7, 25)), leaderId).valid()).isTrue();

        RosterImportPreview underflow = service.preview("roster.xlsx", workbook(List.<String[]>of(
                new String[] {"0001-01-01", "ops1", "ops2"})), leaderId);
        assertThat(underflow.valid()).isFalse();
        assertThat(underflow.errors()).containsExactly(
                new RosterImportError(2, "值班日期不能为空或格式无效"));
    }

    @Test
    void previewAuditsStructuralFailuresWithoutStagingRows() {
        RosterImportPreview preview = service.preview("roster.xlsx",
                RosterWorkbookFixture.headerOnlyOrExtraColumn(true), leaderId);

        assertThat(preview.valid()).isFalse();
        UUID failedId = service.history(org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().getFirst().id();
        RosterImportBatch failed = batches.findByIdWithRows(failedId).orElseThrow();
        assertThat(failed.status()).isEqualTo(RosterImportBatch.Status.FAILED);
        assertThat(failed.fileSha256()).matches("[0-9a-f]{64}");
        assertThat(failed.rows()).isEmpty();
        assertThat(batches.findByIdWithErrors(failedId).orElseThrow().errors())
                .extracting(RosterImportErrorRow::message).containsExactly("Excel 模板必须恰好包含三列表头");
    }

    @Test
    void confirmReplacesOnlyDatesPresentInValidatedBatch() {
        UUID originalSecond = users.findByUsername("ops3").orElseThrow().id();
        UUID originalThird = users.findByUsername("ops1").orElseThrow().id();
        rosters.save(DutyRoster.of(LocalDate.parse("2026-07-27"), originalSecond, originalThird));
        UUID batchId = validPreview(
                new String[] {"2026-07-25", "ops1", "ops2"},
                new String[] {"2026-07-26", "ops2", "ops3"});

        service.confirm(batchId, leaderId);

        assertThat(service.dutyFor(LocalDate.parse("2026-07-25")))
                .isEqualTo(new DutyRosterView(
                        LocalDate.parse("2026-07-25"),
                        users.findByUsername("ops1").orElseThrow().id(),
                        users.findByUsername("ops2").orElseThrow().id()));
        assertThat(rosters.findByDutyDate(LocalDate.parse("2026-07-27")).orElseThrow()
                .secondLineId()).isEqualTo(originalSecond);
        assertThat(batches.findById(batchId).orElseThrow().status())
                .isEqualTo(RosterImportBatch.Status.IMPORTED);
    }

    @Test
    void onlyOneConcurrentConfirmationImportsABatch() throws Exception {
        UUID batchId = validPreview(new String[] {"2026-07-25", "ops1", "ops2"});
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = List.of(
                    executor.submit(() -> confirmAfter(start, batchId)),
                    executor.submit(() -> confirmAfter(start, batchId)));
            start.countDown();
            assertThat(results.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            })).containsExactlyInAnyOrder("IMPORTED", "CONFLICT");
        }
    }

    private String confirmAfter(CountDownLatch start, UUID batchId) throws InterruptedException {
        start.await();
        try {
            service.confirm(batchId, leaderId);
            return "IMPORTED";
        } catch (RosterImportService.ImportConflictException exception) {
            return "CONFLICT";
        }
    }

    private UUID validPreview(String[]... rows) {
        return service.preview("roster.xlsx", workbook(List.of(rows)), leaderId).batchId();
    }

    private UserAccount createUser(String username, Set<RoleName> roles) {
        return users.save(UserAccount.create(
                username,
                username,
                passwordEncoder.encode("Test-Password-1"),
                roles,
                false));
    }

    private byte[] workbook(List<String[]> rows) {
        return RosterWorkbookFixture.workbook(rows);
    }
}
