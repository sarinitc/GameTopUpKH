package org.example.gametopupkh.payment.aba.dto;

import java.math.BigDecimal;

/**
 * Legacy configurable adapter create payload. The requested class name does not
 * imply these fields implement the verified ABA PayWay QR protocol.
 */
public record AbaQrRequest(
        String orderId,
        BigDecimal amount,
        String currency,
        String customerReference,
        String callbackUrl,
        String description,
        String merchantId) {
}
