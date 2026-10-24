package org.example.gametopupkh.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    java.util.List<Payment> findAllByOrderByCreatedAtDesc();

    java.util.List<Payment> findAllByStatusOrderByCreatedAtDesc(PaymentStatus status);

    Optional<Payment> findByOrderId(UUID orderId);

    Optional<Payment> findByReference(String reference);

    Optional<Payment> findByGatewayReference(String gatewayReference);

    boolean existsByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findForUpdateByOrderId(@Param("orderId") UUID orderId);

    /**
     * Payments that have not reached a final state and are old enough to reconcile.
     * The cooldown keeps the scheduled job from re-checking a payment that a
     * callback or staff reconciliation just inspected.
     */
    @Query("""
            select p.id from Payment p
            where p.provider = :provider
              and p.status in (org.example.gametopupkh.payment.PaymentStatus.INITIALIZING,
                               org.example.gametopupkh.payment.PaymentStatus.PENDING,
                               org.example.gametopupkh.payment.PaymentStatus.NEEDS_REVIEW)
              and p.createdAt <= :createdBefore
              and (p.lastProviderCheckAt is null or p.lastProviderCheckAt <= :checkedBefore)
            order by p.createdAt asc, p.id asc
            """)
    List<UUID> findReconciliationCandidates(
            @Param("provider") PaymentProvider provider,
            @Param("createdBefore") Instant createdBefore,
            @Param("checkedBefore") Instant checkedBefore,
            Pageable pageable);
}
