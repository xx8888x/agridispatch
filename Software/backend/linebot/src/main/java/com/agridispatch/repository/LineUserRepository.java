package com.agridispatch.repository;

import com.agridispatch.entity.LineUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LineUserRepository extends JpaRepository<LineUser, String> {
    Optional<LineUser> findByLineUserId(String lineUserId);
    Optional<LineUser> findByUsername(String username);
    List<LineUser> findByRole(String role);
}
