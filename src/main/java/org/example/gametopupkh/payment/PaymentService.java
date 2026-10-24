package org.example.gametopupkh.payment;

import java.util.List;
import java.util.UUID;

import org.example.gametopupkh.payment.dto.CreatePaymentResponse;
import org.example.gametopupkh.payment.dto.PaymentStatusResponse;

/**
 * Payment application operations. Reads are served from persisted state only;
 * provider calls happen for creation, callbacks and authorized reconciliation.
 */
public interface PaymentService {

    /**
     * Initializes a payment for an order owned by the caller.
     *
     * @param orderId the order that is waiting for payment
     * @param userId  the authenticated customer
     * @return the stable reference and QR payload, or the existing one for this order
     */
    CreatePaymentResponse createAbaPayment(UUID orderId, UUID userId);

    /**
     * Reads a payment without contacting the provider.
     *
     * @param paymentId  the payment to read
     * @param requesterId the authenticated caller
     * @param admin      whether the caller may inspect any payment
     */
    PaymentStatusResponse getPayment(UUID paymentId, UUID requesterId, boolean admin);

    /**
     * Completes a payment through the in-memory mock gateway. This is only available
     * while the mock gateway is active and is used by the local success endpoints.
     *
     * @param identifier the payment id, local reference or gateway reference
     */
    PaymentStatusResponse confirmMockPayment(String identifier);

    /**
     * Handles one provider callback. The callback is only a trigger: the authoritative
     * state is always read from the provider, and an event key is processed once.
     *
     * @param reference the local or provider transaction reference
     * @param eventKey  the provider event identifier used for idempotency
     */
    void processCallback(String reference, String eventKey);

    /**
     * Re-reads a payment from the provider on behalf of authorized staff. Repeated
     * calls are throttled per payment to protect the provider API, and every attempt
     * is kept in the payment audit trail.
     *
     * @param paymentId the payment to investigate
     * @param adminId   the authenticated staff member
     */
    PaymentStatusResponse reconcile(UUID paymentId, UUID adminId);

    /**
     * Re-reads a payment from the provider without an interactive staff member.
     * This is the entry point used by scheduled reconciliation. It applies the same
     * amount/currency verification, transactional state changes, audit events and
     * after-commit fulfillment as the staff-triggered path.
     *
     * @param paymentId the unresolved payment to inspect
     */
    PaymentStatusResponse reconcileUnattended(UUID paymentId);

    List<PaymentStatusResponse> findAll(PaymentStatus status);
}
