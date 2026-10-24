package org.example.gametopupkh.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = "user")
    Optional<Order> findWithUserById(UUID id);

    List<Order> findAllByUser_IdOrderByCreatedAtDesc(UUID userId);

    List<Order> findAllByOrderByCreatedAtDesc();

    List<Order> findAllByStatusOrderByCreatedAtDesc(OrderStatus status);

    boolean existsByOrderNumber(String orderNumber);
}
