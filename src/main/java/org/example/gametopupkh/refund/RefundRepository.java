package org.example.gametopupkh.refund;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<PaymentRefund, UUID> {

    Optional<PaymentRefund> findByPaymentId(UUID paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentRefund r where r.id = :id")
    Optional<PaymentRefund> findByIdForUpdate(@Param("id") UUID id);

    List<PaymentRefund> findAllByOrderByCreatedAtDesc();

    List<PaymentRefund> findAllByStatusOrderByCreatedAtDesc(RefundStatus status);

    List<PaymentRefund> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId);
}
