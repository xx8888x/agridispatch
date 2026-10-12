package com.agridispatch.repository;

import com.agridispatch.entity.Farm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FarmRepository extends JpaRepository<Farm, String> {
    List<Farm> findByFarmerIdOrderByCreatedAtAscIdAsc(String farmerId);
    long countByFarmerId(String farmerId);
}
