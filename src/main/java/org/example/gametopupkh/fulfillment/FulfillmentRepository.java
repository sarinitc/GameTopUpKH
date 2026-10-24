package org.example.gametopupkh.fulfillment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FulfillmentRepository extends JpaRepository<Fulfillment, UUID> {

    Optional<Fulfillment> findByOrderId(UUID orderId);

    List<Fulfillment> findAllByStatusOrderByCreatedAtAsc(FulfillmentStatus status);

    List<Fulfillment> findAllByOrderByCreatedAtDesc();

    List<Fulfillment> findAllByStatusOrderByCreatedAtDesc(FulfillmentStatus status);

    @Query("""
            select o.id from Order o
            left join Fulfillment f on f.orderId = o.id
            where (o.status = org.example.gametopupkh.order.OrderStatus.PAID
                and (f.id is null or f.status = org.example.gametopupkh.fulfillment.FulfillmentStatus.PENDING))
               or (o.status = org.example.gametopupkh.order.OrderStatus.PROCESSING
                and f.status = org.example.gametopupkh.fulfillment.FulfillmentStatus.PENDING)
            order by o.createdAt asc, o.id asc
            """)
    List<UUID> findPendingOrderIds(Pageable pageable);
}
