package com.agridispatch.repository;

import com.agridispatch.entity.WeatherState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WeatherStateRepository extends JpaRepository<WeatherState, String> {
    Optional<WeatherState> findByScenarioId(String scenarioId);
    Optional<WeatherState> findFirstByOrderByUpdatedAtDesc();
}
