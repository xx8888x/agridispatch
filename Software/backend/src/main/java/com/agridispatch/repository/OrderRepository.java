package com.agridispatch.repository;

import com.agridispatch.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByStatus(String status);
    List<Order> findByAssignedTeamId(String assignedTeamId);
}
