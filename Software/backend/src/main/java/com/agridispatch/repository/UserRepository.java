package com.agridispatch.repository;

import com.agridispatch.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {
    List<User> findByRoleAndRefId(String role, String refId);

    /** Token 驗證用：只取 token 版本（帳號不存在回 empty） */
    @Query("SELECT COALESCE(u.tokenVersion, 0) FROM User u WHERE u.username = :username")
    Optional<Integer> findTokenVersion(@Param("username") String username);
}
