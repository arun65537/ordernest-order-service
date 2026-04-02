package com.ordernest.order.repository;

import com.ordernest.order.entity.OrderEventHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEventHistoryRepository extends JpaRepository<OrderEventHistory, UUID> {
    List<OrderEventHistory> findByOrderIdOrderByOccurredAtAsc(UUID orderId);
}
