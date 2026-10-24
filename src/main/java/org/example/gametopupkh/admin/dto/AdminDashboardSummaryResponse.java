package org.example.gametopupkh.admin.dto;

import java.math.BigDecimal;

public record AdminDashboardSummaryResponse(
        long totalOrders,
        long pendingPaymentOrders,
        long paidOrders,
        long processingOrders,
        long completedOrders,
        long failedOrders,
        long cancelledOrders,
        long needsReviewOrders,
        long refundedOrders,
        BigDecimal totalRevenue,
        BigDecimal verifiedRevenue,
        BigDecimal successfulRefunds,
        BigDecimal pendingTransactionAmount,
        long totalPayments,
        long completedPayments,
        long pendingPayments,
        long failedPayments,
        long needsReviewPayments,
        long totalRefunds,
        long pendingRefunds,
        long succeededRefunds,
        long failedRefunds,
        long totalFulfillments,
        long pendingFulfillments,
        long processingFulfillments,
        long succeededFulfillments,
        long failedFulfillments
) {
}
