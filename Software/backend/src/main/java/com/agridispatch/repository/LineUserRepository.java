package com.agridispatch.repository;

import com.agridispatch.entity.LineUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LineUserRepository extends JpaRepository<LineUser, String> {
    Optional<LineUser> findByChannelAndLineUserId(String channel, String lineUserId);
    List<LineUser> findAllByChannelAndIsBound(String channel, String isBound);
    List<LineUser> findAllByOrderByChannelAscBoundAtDesc();
}
