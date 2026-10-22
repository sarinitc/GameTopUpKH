package org.example.gametopupkh.order;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findAllByUser_IdOrderByCreatedAtDesc(UUID userId);

    List<Order> findAllByOrderByCreatedAtDesc();

    List<Order> findAllByStatusOrderByCreatedAtDesc(OrderStatus status);

    boolean existsByOrderNumber(String orderNumber);
}
