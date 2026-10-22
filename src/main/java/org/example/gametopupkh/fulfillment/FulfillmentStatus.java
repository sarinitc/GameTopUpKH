package org.example.gametopupkh.fulfillment;

/**
 * Lifecycle of a supplier fulfilment. A failed fulfilment leaves the order in
 * {@code NEEDS_REVIEW} so staff can retry it without taking another payment.
 */
public enum FulfillmentStatus {

    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
