package org.example.gametopupkh.order;

/**
 * Lifecycle of an order. The values match the check constraint of the orders table.
 */
public enum OrderStatus {

    PENDING_PAYMENT,
    PAID,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    REFUNDED,
    NEEDS_REVIEW
}
