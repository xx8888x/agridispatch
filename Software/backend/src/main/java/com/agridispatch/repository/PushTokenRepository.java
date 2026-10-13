package com.agridispatch.repository;

import com.agridispatch.entity.PushToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PushTokenRepository extends JpaRepository<PushToken, String> {
    List<PushToken> findByRefIdIgnoreCase(String refId);
    List<PushToken> findByUsername(String username);
    List<PushToken> findByRole(String role);
}
