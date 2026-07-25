package com.acme.opsqueue.roster;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "duty_rosters")
public class DutyRoster {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "duty_date", nullable = false, unique = true)
    private LocalDate dutyDate;

    @Column(name = "second_line_user_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID secondLineId;

    @Column(name = "third_line_user_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID thirdLineId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DutyRoster() {
    }

    private DutyRoster(LocalDate dutyDate, UUID secondLineId, UUID thirdLineId) {
        this.id = UUID.randomUUID();
        this.dutyDate = dutyDate;
        this.secondLineId = secondLineId;
        this.thirdLineId = thirdLineId;
    }

    public static DutyRoster of(LocalDate dutyDate, UUID secondLineId, UUID thirdLineId) {
        if (secondLineId.equals(thirdLineId)) {
            throw new IllegalArgumentException("Duty users must be different");
        }
        return new DutyRoster(dutyDate, secondLineId, thirdLineId);
    }

    @PrePersist
    void createTimestamps() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }

    public LocalDate dutyDate() { return dutyDate; }
    public UUID secondLineId() { return secondLineId; }
    public UUID thirdLineId() { return thirdLineId; }
}
