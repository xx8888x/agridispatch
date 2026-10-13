package com.agridispatch.repository;

import com.agridispatch.entity.WorkerLeave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface WorkerLeaveRepository extends JpaRepository<WorkerLeave, String> {
    List<WorkerLeave> findByWorkerIdOrderByStartDateDesc(String workerId);

    List<WorkerLeave> findByStatusIn(Collection<String> statuses);

    List<WorkerLeave> findByWorkerIdAndStatusIn(String workerId, Collection<String> statuses);

    @Query("SELECT l.id FROM WorkerLeave l WHERE l.id LIKE CONCAT(:prefix, '%')")
    List<String> findIdsByPrefix(@Param("prefix") String prefix);
}
