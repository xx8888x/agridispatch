package com.agridispatch.repository;

import com.agridispatch.entity.FinanceVoucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FinanceVoucherRepository extends JpaRepository<FinanceVoucher, Long> {
    /** 同月份已有的傳票號碼（產生流水號用） */
    @Query("SELECT v.voucherNo FROM FinanceVoucher v WHERE v.voucherNo LIKE CONCAT(:prefix, '%')")
    List<String> findNosByPrefix(@Param("prefix") String prefix);
}
