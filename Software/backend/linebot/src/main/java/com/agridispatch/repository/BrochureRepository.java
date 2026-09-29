package com.agridispatch.repository;

import com.agridispatch.entity.Brochure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BrochureRepository extends JpaRepository<Brochure, String> {
    List<Brochure> findByStatus(String status);
}
