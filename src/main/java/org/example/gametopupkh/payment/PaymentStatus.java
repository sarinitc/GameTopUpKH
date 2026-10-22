package org.example.gametopupkh.payment;

/**
 * Lifecycle state of a payment as reported by a gateway.
 */
public enum PaymentStatus {

    /**
     * A local reference has been reserved and the provider has not answered yet.
     */
    INITIALIZING,
    PENDING,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED,
    /**
     * The provider state cannot be trusted automatically, for example because the
     * paid amount does not match the order or the order was cancelled in the
     * meantime. Staff must investigate before the order is fulfilled.
     */
    NEEDS_REVIEW;

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == EXPIRED || this == CANCELLED;
    }

    public boolean isSuccessful() {
        return this == COMPLETED;
    }
}
