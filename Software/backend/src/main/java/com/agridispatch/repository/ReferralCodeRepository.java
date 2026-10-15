package com.agridispatch.repository;

import com.agridispatch.entity.ReferralCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReferralCodeRepository extends JpaRepository<ReferralCode, String> {
    Optional<ReferralCode> findByOwnerUsername(String ownerUsername);

    /** 原子 +1，避免同時開啟時漏算 */
    @Modifying
    @Query("UPDATE ReferralCode c SET c.clickCount = c.clickCount + 1 WHERE c.code = :code")
    int incrementClicks(@Param("code") String code);
}
