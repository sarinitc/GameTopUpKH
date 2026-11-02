package org.example.gametopupkh.payment.bakong.dto;

import java.util.UUID;

/**
 * Request payload when initializing a Bakong payment via {@code /api/v1/payments/bakong/khqr}.
 */
public record BakongKhqrOrderRequest(UUID orderId) {
}
