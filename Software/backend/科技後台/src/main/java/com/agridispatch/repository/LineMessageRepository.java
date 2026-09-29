package com.agridispatch.repository;

import com.agridispatch.entity.LineMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LineMessageRepository extends JpaRepository<LineMessage, String> {
    List<LineMessage> findByLineUserIdOrderByCreatedAtDesc(String lineUserId);
    List<LineMessage> findByTargetRoleOrderByCreatedAtDesc(String targetRole);
    List<LineMessage> findTop20ByOrderByCreatedAtDesc();
}
