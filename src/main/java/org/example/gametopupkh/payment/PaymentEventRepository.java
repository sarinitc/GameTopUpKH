package org.example.gametopupkh.payment;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    List<PaymentEvent> findAllByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    boolean existsByPaymentIdAndEventKey(UUID paymentId, String eventKey);
}
