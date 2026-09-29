package com.agridispatch.repository;

import com.agridispatch.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByStatus(String status);
    List<Order> findByAssignedTeamId(String assignedTeamId);

    /** 取出指定前綴的訂單編號（產生不重複序號用） */
    @Query("SELECT o.id FROM Order o WHERE o.id LIKE CONCAT(:prefix, '%')")
    List<String> findIdsByPrefix(@Param("prefix") String prefix);
}
