package com.agridispatch.repository;

import com.agridispatch.entity.Worker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkerRepository extends JpaRepository<Worker, String> {
    List<Worker> findByTeamId(String teamId);
    List<Worker> findByTier(String tier);
    Optional<Worker> findByArcNumber(String arcNumber);
    Optional<Worker> findByPassportNumber(String passportNumber);
    Optional<Worker> findByPhone(String phone);
}
