package com.acme.opsqueue.roster;

import com.acme.opsqueue.identity.RoleName;
import com.acme.opsqueue.identity.UserAccount;
import com.acme.opsqueue.identity.UserAccountRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RosterImportService {
    private static final String INVALID_DATE = "值班日期不能为空或格式无效";
    private final RosterExcelParser parser;
    private final UserAccountRepository users;
    private final DutyRosterRepository rosters;
    private final RosterImportBatchRepository batches;

    public RosterImportService(RosterExcelParser parser, UserAccountRepository users,
            DutyRosterRepository rosters, RosterImportBatchRepository batches) {
        this.parser = parser;
        this.users = users;
        this.rosters = rosters;
        this.batches = batches;
    }

    @Transactional
    public RosterImportPreview preview(String originalFilename, byte[] bytes, UUID uploaderId) {
        RosterExcelParser.ParsedWorkbook parsed = parser.parse(bytes);
        if (!parsed.errors().isEmpty()) {
            persistFailure(originalFilename, bytes, uploaderId, parsed.rows(), parsed.errors());
            return new RosterImportPreview(null, false, parsed.errors());
        }
        Map<String, UserAccount> accounts = accountsByUsername(parsed.rows());
        List<RosterImportError> errors = validate(parsed.rows(), accounts);
        if (!errors.isEmpty()) {
            persistFailure(originalFilename, bytes, uploaderId, parsed.rows(), errors);
            return new RosterImportPreview(null, false, errors);
        }
        RosterImportBatch batch = RosterImportBatch.validated(
                safeFilename(originalFilename), sha256(bytes), uploaderId);
        for (RosterExcelParser.ParsedRow row : parsed.rows()) {
            batch.addRow(row.sourceRowNumber(), row.parseDate(),
                    accounts.get(normalize(row.secondLineUsername())).id(),
                    accounts.get(normalize(row.thirdLineUsername())).id());
        }
        batches.saveAndFlush(batch);
        return new RosterImportPreview(batch.id(), true, List.of());
    }

    private void persistFailure(String originalFilename, byte[] bytes, UUID uploaderId,
            List<RosterExcelParser.ParsedRow> rows, List<RosterImportError> errors) {
        String dates = rows.stream().map(RosterExcelParser.ParsedRow::parseDate)
                .filter(java.util.Objects::nonNull).map(LocalDate::toString).distinct().sorted()
                .collect(java.util.stream.Collectors.joining(","));
        batches.saveAndFlush(RosterImportBatch.failed(safeFilename(originalFilename), sha256(bytes),
                uploaderId, rows.size(), dates, errors));
    }

    @Transactional
    public void confirm(UUID batchId, UUID confirmerId) {
        RosterImportBatch batch = batches.findByIdForUpdate(batchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "值班表导入批次不存在"));
        if (batch.status() != RosterImportBatch.Status.VALIDATED) {
            throw new ImportConflictException();
        }
        Set<UUID> userIds = new HashSet<>();
        for (RosterImportRow row : batch.rows()) {
            userIds.add(row.secondLineUserId());
            userIds.add(row.thirdLineUserId());
        }
        Map<UUID, UserAccount> lockedAccounts = new HashMap<>();
        for (UserAccount account : users.findAllByIdInForUpdate(userIds)) {
            lockedAccounts.put(account.id(), account);
        }
        if (!allEnabledOperators(userIds, lockedAccounts)) {
            throw new ImportConflictException("值班表账号状态或角色已变化，请重新预览导入");
        }
        List<LocalDate> dates = batch.rows().stream().map(RosterImportRow::dutyDate).toList();
        rosters.deleteByDutyDateIn(dates);
        rosters.flush();
        rosters.saveAll(batch.rows().stream().map(row -> DutyRoster.of(
                row.dutyDate(), row.secondLineUserId(), row.thirdLineUserId())).toList());
        batch.markImported(confirmerId);
    }

    @Transactional(readOnly = true)
    public DutyRosterView dutyFor(LocalDate date) {
        return rosters.findByDutyDate(date).map(DutyRosterView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该日期值班表"));
    }

    @Transactional(readOnly = true)
    public List<DutyRosterView> allRosters() {
        return rosters.findAllByOrderByDutyDateAsc().stream().map(DutyRosterView::from).toList();
    }

    @Transactional(readOnly = true)
    public Page<RosterImportBatchView> history(Pageable pageable) {
        return batches.findAllByOrderByCreatedAtDesc(pageable).map(RosterImportBatchView::from);
    }

    private Map<String, UserAccount> accountsByUsername(Collection<RosterExcelParser.ParsedRow> rows) {
        Set<String> usernames = new HashSet<>();
        for (RosterExcelParser.ParsedRow row : rows) {
            usernames.add(normalize(row.secondLineUsername()));
            usernames.add(normalize(row.thirdLineUsername()));
        }
        Map<String, UserAccount> accounts = new HashMap<>();
        users.findByUsernameIn(usernames).forEach(account -> accounts.put(account.username(), account));
        return accounts;
    }

    private List<RosterImportError> validate(List<RosterExcelParser.ParsedRow> rows,
            Map<String, UserAccount> accounts) {
        List<RosterImportError> errors = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        for (RosterExcelParser.ParsedRow row : rows) {
            LocalDate date = row.parseDate();
            if (date == null) {
                errors.add(new RosterImportError(row.sourceRowNumber(), INVALID_DATE));
                continue;
            }
            if (!dates.add(date)) {
                errors.add(new RosterImportError(row.sourceRowNumber(), "值班日期在文件中重复"));
                continue;
            }
            String second = normalize(row.secondLineUsername());
            String third = normalize(row.thirdLineUsername());
            if (second.equals(third)) {
                errors.add(new RosterImportError(row.sourceRowNumber(), "二线管理员账号和三线管理员账号不能相同"));
                continue;
            }
            String secondError = accountError(accounts.get(second), "二线管理员账号");
            if (secondError != null) {
                errors.add(new RosterImportError(row.sourceRowNumber(), secondError));
                continue;
            }
            String thirdError = accountError(accounts.get(third), "三线管理员账号");
            if (thirdError != null) {
                errors.add(new RosterImportError(row.sourceRowNumber(), thirdError));
            }
        }
        return errors;
    }

    private String accountError(UserAccount account, String label) {
        if (account == null || !account.enabled()) {
            return label + "不存在或已停用";
        }
        if (!account.hasRole(RoleName.OPERATOR)) {
            return label + "不具有运维管理员角色";
        }
        return null;
    }

    private boolean allEnabledOperators(Set<UUID> expected, Map<UUID, UserAccount> accounts) {
        return accounts.size() == expected.size()
                && accounts.values().stream().allMatch(account -> account.enabled()
                        && account.hasRole(RoleName.OPERATOR));
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String safeFilename(String originalFilename) {
        return (originalFilename == null || originalFilename.isBlank()) ? "roster.xlsx" : originalFilename;
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder encoded = new StringBuilder(64);
            for (byte value : digest) {
                encoded.append(String.format("%02x", value));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static class ImportConflictException extends RuntimeException {
        public ImportConflictException() { super("值班表导入批次已确认"); }
        public ImportConflictException(String message) { super(message); }
    }
}
