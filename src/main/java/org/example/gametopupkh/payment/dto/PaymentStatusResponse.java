package org.example.gametopupkh.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.payment.Payment;
import org.example.gametopupkh.payment.PaymentProvider;
import org.example.gametopupkh.payment.PaymentStatus;

/**
 * Authenticated read model for a payment. It never performs a provider call.
 */
public record PaymentStatusResponse(
        UUID paymentId,
        UUID orderId,
        PaymentProvider provider,
        String reference,
        String gatewayReference,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String qrString,
        String qrImageUrl,
        String redirectUrl,
        Instant expiresAt,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt) {

    public String qrImage() {
        return qrImageUrl;
    }

    public static PaymentStatusResponse from(Payment payment) {
        return new PaymentStatusResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getProvider(),
                payment.getReference(),
                payment.getGatewayReference(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getQrString(),
                payment.getQrImageUrl(),
                payment.getRedirectUrl(),
                payment.getExpiresAt(),
                payment.getPaidAt(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
