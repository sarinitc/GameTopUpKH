package org.example.gametopupkh.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;

import org.example.gametopupkh.payment.PaymentStatus;

/**
 * Abstraction over an external payment provider.
 * <p>
 * Implementations are selected with the {@code payment.gateway} property; only one
 * implementation is active at a time.
 */
public interface PaymentGateway {

    /**
     * Short name of the gateway, persisted alongside the payment record.
     */
    String name();

    /**
     * Starts a payment and returns the intent to fulfil it.
     *
     * @throws PaymentGatewayException when the provider rejects the request
     */
    PaymentIntent createPayment(PaymentRequest request);

    /**
     * Looks up the current state of a payment started through this gateway.
     *
     * @throws PaymentGatewayException when the provider cannot be reached
     */
    PaymentIntent checkStatus(String gatewayReference);

    /**
     * Provider-neutral request to start a payment. Kept with the gateway contract
     * so provider transport types stay inside their own packages.
     * <p>
     * {@code transactionId} is the stable local reference reserved before any
     * network call. It is safe to retry with the same value.
     */
    record PaymentRequest(
            String transactionId,
            BigDecimal amount,
            String currency,
            String customerReference,
            String callbackUrl,
            String description) {
    }

    /**
     * Result returned by a gateway, including its reference and current state.
     */
    record PaymentIntent(
            String gateway,
            String gatewayReference,
            PaymentStatus status,
            BigDecimal amount,
            String currency,
            String redirectUrl,
            Instant createdAt,
            String qrString,
            String qrImageUrl,
            Instant expiresAt) {

        public PaymentIntent(String gateway, String gatewayReference, PaymentStatus status, BigDecimal amount,
                String currency, String redirectUrl, Instant createdAt) {
            this(gateway, gatewayReference, status, amount, currency, redirectUrl, createdAt, null, null, null);
        }
    }

    /** Raised when a gateway call fails or returns an unusable response. */
    class PaymentGatewayException extends RuntimeException {

        private final String gateway;

        public PaymentGatewayException(String gateway, String message) {
            super(message);
            this.gateway = gateway;
        }

        public PaymentGatewayException(String gateway, String message, Throwable cause) {
            super(message, cause);
            this.gateway = gateway;
        }

        public String getGateway() {
            return gateway;
        }
    }
}
