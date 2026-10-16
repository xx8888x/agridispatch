package com.agridispatch.repository;

import com.agridispatch.entity.FinanceVoucherLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FinanceVoucherLineRepository extends JpaRepository<FinanceVoucherLine, Long> {
    List<FinanceVoucherLine> findByVoucherIdOrderByLineNo(Long voucherId);
}
