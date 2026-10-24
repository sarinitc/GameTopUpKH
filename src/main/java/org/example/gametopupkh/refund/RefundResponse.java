package org.example.gametopupkh.refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.payment.PaymentProvider;

/** Immutable view of a refund request and its provider state. */
public record RefundResponse(
        UUID refundId,
        UUID paymentId,
        UUID orderId,
        PaymentProvider provider,
        RefundStatus status,
        BigDecimal amount,
        String currency,
        String reason,
        String providerReference,
        UUID requestedBy,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static RefundResponse from(PaymentRefund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getOrderId(),
                refund.getProvider(),
                refund.getStatus(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getReason(),
                refund.getProviderReference(),
                refund.getRequestedBy(),
                refund.getCompletedAt(),
                refund.getCreatedAt(),
                refund.getUpdatedAt());
    }
}
