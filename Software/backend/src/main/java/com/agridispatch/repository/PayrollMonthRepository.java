package com.agridispatch.repository;

import com.agridispatch.entity.PayrollMonth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PayrollMonthRepository extends JpaRepository<PayrollMonth, String> {
}
