package com.acme.opsqueue.roster;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "roster_import_errors")
class RosterImportErrorRow {
    @Id @Column(columnDefinition = "BINARY(16)") private UUID id;
    @ManyToOne(optional = false) @JoinColumn(name = "batch_id") private RosterImportBatch batch;
    @Column(name = "source_row_number", nullable = false) private int sourceRowNumber;
    @Column(nullable = false, length = 255) private String message;
    protected RosterImportErrorRow() { }
    RosterImportErrorRow(RosterImportBatch batch, int sourceRowNumber, String message) {
        this.id = UUID.randomUUID(); this.batch = batch; this.sourceRowNumber = sourceRowNumber; this.message = message;
    }
    int sourceRowNumber() { return sourceRowNumber; }
    String message() { return message; }
}
