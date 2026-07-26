package com.acme.opsqueue.roster;

import com.acme.opsqueue.audit.AuditService;
import com.acme.opsqueue.identity.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/rosters")
public class RosterController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final RosterImportService rosterImports;
    private final AuditService audits;
    private final Clock clock;

    public RosterController(
            RosterImportService rosterImports, AuditService audits, Clock clock) {
        this.rosterImports = rosterImports;
        this.audits = audits;
        this.clock = clock;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=roster-template.xlsx")
                .contentType(MediaType.parseMediaType(XLSX))
                .body(templateBytes());
    }

    @PostMapping(value = "/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RosterImportPreview> preview(@RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal CurrentUser currentUser) throws IOException {
        RosterImportPreview preview = rosterImports.preview(
                file.getOriginalFilename(), file.getBytes(), currentUser.id());
        if (!preview.valid()) {
            boolean limit = preview.errors().stream().anyMatch(error -> error.message().equals("上传文件超过安全限制"));
            return ResponseEntity.status(limit ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.UNPROCESSABLE_ENTITY).body(preview);
        }
        return ResponseEntity.ok(preview);
    }

    @PostMapping("/imports/{batchId}/confirm")
    @Transactional
    public ResponseEntity<Void> confirm(@PathVariable UUID batchId,
            @AuthenticationPrincipal CurrentUser currentUser) {
        rosterImports.confirm(batchId, currentUser.id());
        audits.recordCurrentRequest(
                currentUser.id(), "ROSTER_CONFIRMED", "ROSTER_IMPORT", batchId,
                Map.of("status", "VALIDATED"), Map.of("status", "IMPORTED"),
                clock.instant());
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<DutyRosterView> rosters() {
        return rosterImports.allRosters();
    }

    @GetMapping("/imports")
    public Page<RosterImportBatchView> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100));
        return rosterImports.history(pageable);
    }

    @GetMapping("/imports/{batchId}")
    public RosterImportBatchDetailView importDetail(@PathVariable UUID batchId) {
        return rosterImports.importDetail(batchId);
    }

    @ExceptionHandler(RosterImportService.ImportConflictException.class)
    public ResponseEntity<String> importConflict(RosterImportService.ImportConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.getMessage());
    }

    private byte[] templateBytes() {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var header = workbook.createSheet("值班表").createRow(0);
            for (int column = 0; column < RosterExcelParser.REQUIRED_HEADERS.size(); column++) {
                header.createCell(column).setCellValue(RosterExcelParser.REQUIRED_HEADERS.get(column));
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成值班表模板", exception);
        }
    }
}
