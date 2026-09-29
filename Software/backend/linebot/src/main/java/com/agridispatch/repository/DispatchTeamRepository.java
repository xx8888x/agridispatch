package com.agridispatch.repository;

import com.agridispatch.entity.DispatchTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DispatchTeamRepository extends JpaRepository<DispatchTeam, String> {
}
