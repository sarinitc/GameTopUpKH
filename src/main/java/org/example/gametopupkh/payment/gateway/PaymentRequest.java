package org.example.gametopupkh.payment.gateway;

import java.math.BigDecimal;

/**
 * Request handed to a {@link PaymentGateway} to start a payment.
 *
 * @param orderId          internal order identifier the payment belongs to
 * @param amount           amount to charge
 * @param currency         ISO-4217 currency code
 * @param customerReference payer reference (player id, user id, phone number)
 * @param callbackUrl      absolute URL the gateway posts the result to
 * @param description      human readable description shown to the payer
 */
public record PaymentRequest(
        String orderId,
        BigDecimal amount,
        String currency,
        String customerReference,
        String callbackUrl,
        String description) {
}
