package org.example.gametopupkh.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Result of a payment attempt returned by a {@link PaymentGateway}.
 *
 * @param gateway          name of the gateway that produced this result
 * @param gatewayReference identifier assigned by the gateway, used for lookups
 * @param status           current status of the payment
 * @param amount           amount that was requested
 * @param currency         ISO-4217 currency code
 * @param redirectUrl      URL the payer is redirected to, {@code null} for callbacks only
 * @param createdAt        instant the payment was created
 */
public record PaymentIntent(
        String gateway,
        String gatewayReference,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String redirectUrl,
        Instant createdAt) {
}
