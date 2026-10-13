package com.agridispatch.repository;

import com.agridispatch.entity.PayrollSlip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PayrollSlipRepository extends JpaRepository<PayrollSlip, String> {
    List<PayrollSlip> findByMonthOrderByWorkerIdAsc(String month);

    void deleteByMonth(String month);
}
