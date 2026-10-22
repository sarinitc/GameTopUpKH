package org.example.gametopupkh.order;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

    List<OrderStatusHistory> findAllByOrderIdOrderByChangedAtAsc(UUID orderId);

    List<OrderStatusHistory> findAllByOrderIdInOrderByChangedAtAsc(Collection<UUID> orderIds);
}
