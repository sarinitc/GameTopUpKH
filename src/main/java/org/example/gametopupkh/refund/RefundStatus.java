package org.example.gametopupkh.refund;

/**
 * Lifecycle of a refund request. A pending request is not a returned payment:
 * only a provider-confirmed success may mark the order refunded.
 */
public enum RefundStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
