package com.agridispatch.repository;

import com.agridispatch.entity.TeamRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamRatingRepository extends JpaRepository<TeamRating, String> {
    Optional<TeamRating> findByOrderId(String orderId);
    List<TeamRating> findByTeamIdOrderByCreatedAtDesc(String teamId);
}
