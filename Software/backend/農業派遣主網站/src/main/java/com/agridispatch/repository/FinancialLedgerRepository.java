package com.agridispatch.repository;

import com.agridispatch.entity.FinancialLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FinancialLedgerRepository extends JpaRepository<FinancialLedger, String> {
    Optional<FinancialLedger> findByOrderId(String orderId);

    @Query("SELECT SUM(f.totalGmv) FROM FinancialLedger f")
    Long sumTotalGmv();

    @Query("SELECT SUM(f.techTakeRate) FROM FinancialLedger f")
    Long sumTechTakeRate();

    @Query("SELECT SUM(f.coopPassThrough) FROM FinancialLedger f")
    Long sumCoopPassThrough();
}
