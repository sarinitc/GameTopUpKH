package org.example.gametopupkh.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.payment.Payment;
import org.example.gametopupkh.payment.PaymentProvider;
import org.example.gametopupkh.payment.PaymentStatus;

/**
 * Data returned when a QR payment is initialized. Repeating the request returns the
 * same local reference and QR payload instead of creating a new charge.
 */
public record CreatePaymentResponse(
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
        Instant createdAt) {

    public String qrImage() {
        return qrImageUrl;
    }

    public static CreatePaymentResponse from(Payment payment) {
        return new CreatePaymentResponse(
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
                payment.getCreatedAt());
    }
}
