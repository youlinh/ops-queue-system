package com.acme.opsqueue.roster;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "roster_import_batches")
public class RosterImportBatch {
    public enum Status { VALIDATED, IMPORTED, FAILED }

    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;
    @Column(name = "original_filename", nullable = false)
    private String originalFilename;
    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;
    @Column(name = "row_count", nullable = false)
    private int rowCount;
    @Column(name = "uploaded_by_user_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID uploadedByUserId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "imported_by_user_id", columnDefinition = "BINARY(16)")
    private UUID importedByUserId;
    @Column(name = "imported_at")
    private Instant importedAt;
    @Column(name = "covered_dates", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String coveredDates;
    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RosterImportRow> rows = new ArrayList<>();
    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RosterImportErrorRow> errors = new ArrayList<>();

    protected RosterImportBatch() {
    }

    private RosterImportBatch(String originalFilename, String fileSha256, UUID uploadedByUserId) {
        this.id = UUID.randomUUID();
        this.status = Status.VALIDATED;
        this.originalFilename = originalFilename;
        this.fileSha256 = fileSha256;
        this.uploadedByUserId = uploadedByUserId;
        this.createdAt = Instant.now();
        this.coveredDates = "";
    }

    public static RosterImportBatch validated(
            String originalFilename, String fileSha256, UUID uploadedByUserId) {
        return new RosterImportBatch(originalFilename, fileSha256, uploadedByUserId);
    }

    public static RosterImportBatch failed(String originalFilename, String fileSha256,
            UUID uploadedByUserId, int rowCount, String coveredDates, List<RosterImportError> errors) {
        RosterImportBatch batch = new RosterImportBatch(originalFilename, fileSha256, uploadedByUserId);
        batch.status = Status.FAILED;
        batch.rowCount = rowCount;
        batch.coveredDates = coveredDates;
        errors.forEach(error -> batch.errors.add(new RosterImportErrorRow(batch, error.rowNumber(), error.message())));
        return batch;
    }

    public void addRow(int sourceRowNumber, java.time.LocalDate dutyDate,
            UUID secondLineUserId, UUID thirdLineUserId) {
        rows.add(new RosterImportRow(this, sourceRowNumber, dutyDate, secondLineUserId, thirdLineUserId));
        rowCount = rows.size();
    }

    public void finishStaging() {
        coveredDates = rows.stream().map(row -> row.dutyDate().toString()).sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    public void markImported(UUID confirmerId) {
        if (status != Status.VALIDATED) {
            throw new IllegalStateException("Batch is not validated");
        }
        status = Status.IMPORTED;
        importedByUserId = confirmerId;
        importedAt = Instant.now();
    }

    public UUID id() { return id; }
    public Status status() { return status; }
    public String originalFilename() { return originalFilename; }
    public String fileSha256() { return fileSha256; }
    public int rowCount() { return rowCount; }
    public UUID uploadedByUserId() { return uploadedByUserId; }
    public Instant createdAt() { return createdAt; }
    public UUID importedByUserId() { return importedByUserId; }
    public Instant importedAt() { return importedAt; }
    public List<RosterImportRow> rows() { return List.copyOf(rows); }
    public String coveredDates() { return coveredDates; }
    List<RosterImportErrorRow> errors() { return List.copyOf(errors); }
}
