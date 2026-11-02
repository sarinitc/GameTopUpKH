package org.example.gametopupkh.payment.bakong.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Result of generating a dynamic Bakong KHQR code.
 */
public record BakongKhqrResponse(
        String qr,
        String md5,
        BigDecimal amount,
        String currency,
        Instant expiresAt,
        String paymentReference,
        UUID paymentId,
        UUID orderId) {

    public BakongKhqrResponse(
            String qr,
            String md5,
            BigDecimal amount,
            String currency,
            Instant expiresAt,
            String paymentReference) {
        this(qr, md5, amount, currency, expiresAt, paymentReference, null, null);
    }
}
