package com.agridispatch.repository;

import com.agridispatch.entity.AccountDeletionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountDeletionRequestRepository extends JpaRepository<AccountDeletionRequest, String> {
    List<AccountDeletionRequest> findByUsernameOrderByCreatedAtDesc(String username);
    List<AccountDeletionRequest> findAllByOrderByCreatedAtDesc();

    @Query("SELECT r.id FROM AccountDeletionRequest r WHERE r.id LIKE CONCAT(:prefix, '%')")
    List<String> findIdsByPrefix(@Param("prefix") String prefix);
}
