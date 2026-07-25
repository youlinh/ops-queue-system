package com.acme.opsqueue.roster;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "roster_import_rows")
public class RosterImportRow {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private RosterImportBatch batch;
    @Column(name = "source_row_number", nullable = false)
    private int sourceRowNumber;
    @Column(name = "duty_date", nullable = false)
    private LocalDate dutyDate;
    @Column(name = "second_line_user_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID secondLineUserId;
    @Column(name = "third_line_user_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID thirdLineUserId;

    protected RosterImportRow() {
    }

    RosterImportRow(RosterImportBatch batch, int sourceRowNumber, LocalDate dutyDate,
            UUID secondLineUserId, UUID thirdLineUserId) {
        this.id = UUID.randomUUID();
        this.batch = batch;
        this.sourceRowNumber = sourceRowNumber;
        this.dutyDate = dutyDate;
        this.secondLineUserId = secondLineUserId;
        this.thirdLineUserId = thirdLineUserId;
    }

    public int sourceRowNumber() { return sourceRowNumber; }
    public LocalDate dutyDate() { return dutyDate; }
    public UUID secondLineUserId() { return secondLineUserId; }
    public UUID thirdLineUserId() { return thirdLineUserId; }
}
