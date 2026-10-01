package com.agridispatch.repository;

import com.agridispatch.entity.InsuranceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface InsuranceRecordRepository extends JpaRepository<InsuranceRecord, String> {
    List<InsuranceRecord> findByWorkerIdOrderByEnrollDateDesc(String workerId);
    List<InsuranceRecord> findByWorkerIdIn(Collection<String> workerIds);
}
