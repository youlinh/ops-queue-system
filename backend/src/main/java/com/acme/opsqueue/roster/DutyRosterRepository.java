package com.acme.opsqueue.roster;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DutyRosterRepository extends JpaRepository<DutyRoster, UUID> {
    Optional<DutyRoster> findByDutyDate(LocalDate dutyDate);
    List<DutyRoster> findAllByOrderByDutyDateAsc();
    void deleteByDutyDateIn(Collection<LocalDate> dutyDates);
}
