package org.example.gametopupkh.fulfillment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FulfillmentRepository extends JpaRepository<Fulfillment, UUID> {

    Optional<Fulfillment> findByOrderId(UUID orderId);

    List<Fulfillment> findAllByStatusOrderByCreatedAtAsc(FulfillmentStatus status);
}
