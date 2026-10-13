package com.agridispatch.repository;

import com.agridispatch.entity.AttendanceCorrection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface AttendanceCorrectionRepository extends JpaRepository<AttendanceCorrection, Long> {
    List<AttendanceCorrection> findByClockInIdOrderByCreatedAtAsc(Long clockInId);

    List<AttendanceCorrection> findByClockInIdIn(Collection<Long> clockInIds);

    List<AttendanceCorrection> findByWorkDateBetweenOrderByCreatedAtDesc(LocalDate from, LocalDate to);
}
