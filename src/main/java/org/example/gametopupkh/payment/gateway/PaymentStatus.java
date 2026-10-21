package org.example.gametopupkh.payment.gateway;

/**
 * Lifecycle state of a payment as reported by a gateway.
 */
public enum PaymentStatus {

    PENDING,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
