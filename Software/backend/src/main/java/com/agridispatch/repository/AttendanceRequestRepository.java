package com.agridispatch.repository;

import com.agridispatch.entity.AttendanceRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface AttendanceRequestRepository extends JpaRepository<AttendanceRequest, String> {
    List<AttendanceRequest> findByWorkerIdOrderByCreatedAtDesc(String workerId);

    List<AttendanceRequest> findByStatusIn(Collection<String> statuses);

    List<AttendanceRequest> findByWorkerIdAndStatus(String workerId, String status);

    List<AttendanceRequest> findByStatusAndWorkDateBetween(String status, LocalDate from, LocalDate to);

    @Query("SELECT r.id FROM AttendanceRequest r WHERE r.id LIKE CONCAT(:prefix, '%')")
    List<String> findIdsByPrefix(@Param("prefix") String prefix);
}
