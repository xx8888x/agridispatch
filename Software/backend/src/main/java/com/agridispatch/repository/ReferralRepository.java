package com.agridispatch.repository;

import com.agridispatch.entity.Referral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReferralRepository extends JpaRepository<Referral, String> {
    List<Referral> findByReferrerUsernameOrderByCreatedAtDesc(String referrerUsername);
    List<Referral> findAllByOrderByCreatedAtDesc();
    boolean existsByCodeAndInviteePhone(String code, String inviteePhone);

    @Query("SELECT r.id FROM Referral r WHERE r.id LIKE CONCAT(:prefix, '%')")
    List<String> findIdsByPrefix(@Param("prefix") String prefix);
}
