package com.acme.opsqueue.roster;

import jakarta.persistence.LockModeType;
import java.util.List;
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

    Page<RosterImportBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("select error.batch.id, count(error) from RosterImportErrorRow error "
            + "where error.batch.id in :ids group by error.batch.id")
    List<Object[]> countErrorsByBatchId(@Param("ids") List<UUID> ids);
}
