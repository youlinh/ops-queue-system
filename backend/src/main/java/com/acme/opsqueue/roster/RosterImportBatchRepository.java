package com.acme.opsqueue.roster;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RosterImportBatchRepository extends JpaRepository<RosterImportBatch, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select batch from RosterImportBatch batch left join fetch batch.rows where batch.id = :id")
    Optional<RosterImportBatch> findByIdForUpdate(@Param("id") UUID id);

    @Query("select batch from RosterImportBatch batch left join fetch batch.rows where batch.id = :id")
    Optional<RosterImportBatch> findByIdWithRows(@Param("id") UUID id);

    @Query("select batch from RosterImportBatch batch left join fetch batch.errors where batch.id = :id")
    Optional<RosterImportBatch> findByIdWithErrors(@Param("id") UUID id);

    @Query(value = "select distinct batch from RosterImportBatch batch left join fetch batch.errors order by batch.createdAt desc",
            countQuery = "select count(batch) from RosterImportBatch batch")
    Page<RosterImportBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
