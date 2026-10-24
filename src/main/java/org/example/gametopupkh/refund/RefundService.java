package org.example.gametopupkh.refund;

import java.util.List;
import java.util.UUID;

/**
 * Refund requests for completed payments. Requesting a refund does not by itself
 * return money to the customer; provider-confirmed results are tracked separately.
 */
public interface RefundService {

    /**
     * Requests a refund for a completed payment. Repeating the call returns the
     * existing refund for that payment instead of creating another one.
     *
     * @param paymentId the payment to refund
     * @param adminId   the authenticated staff member
     * @param request   optional amount and reason
     */
    RefundResponse requestRefund(UUID paymentId, UUID adminId, RefundRequest request);

    /**
     * @param status optional status filter
     * @return every refund request, newest first
     */
    List<RefundResponse> findAll(RefundStatus status);

    RefundResponse findById(UUID refundId);

    /**
     * Reads the refunds of one order. Customers only see their own order; staff may
     * read any order.
     */
    List<RefundResponse> findByOrder(UUID orderId, UUID requesterId, boolean admin);
}
