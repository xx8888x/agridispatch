package com.agridispatch.repository;

import com.agridispatch.entity.SharedRoster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SharedRosterRepository extends JpaRepository<SharedRoster, String> {
    List<SharedRoster> findByStatusIn(List<String> statuses);

    @Query("SELECT r.id FROM SharedRoster r WHERE r.id LIKE CONCAT(:prefix, '%')")
    List<String> findIdsByPrefix(@Param("prefix") String prefix);
}
