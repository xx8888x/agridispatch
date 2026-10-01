package com.agridispatch.repository;

import com.agridispatch.entity.SkillReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SkillReviewRepository extends JpaRepository<SkillReview, Long> {
    List<SkillReview> findByWorkerIdOrderByClearedAtDesc(String workerId);
}
