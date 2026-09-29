package com.agridispatch.repository;

import com.agridispatch.entity.Farmer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FarmerRepository extends JpaRepository<Farmer, String> {
    Optional<Farmer> findByPhone(String phone);
    List<Farmer> findByFarmType(String farmType);
}
