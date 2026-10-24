package org.example.gametopupkh.fulfillment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Delivers a paid order to the configured supplier. Fulfilment is idempotent per
 * order, so repeated payment callbacks cannot deliver the same top-up twice.
 */
public interface FulfillmentService {

    /**
     * Starts or resumes fulfilment of a paid order. A failed fulfilment is not
     * retried automatically by this method; call {@link #retry(UUID)} instead.
     */
    Fulfillment fulfillOrder(UUID orderId);

    /**
     * Alias for {@link #fulfillOrder(UUID)}.
     */
    default Fulfillment fulfill(UUID orderId) {
        return fulfillOrder(orderId);
    }

    /**
     * Retries a failed or interrupted fulfilment. Successful fulfilments are
     * returned unchanged.
     */
    Fulfillment retry(UUID orderId);

    /**
     * Retries the fulfilment with the given id. The endpoint addresses a
     * fulfilment directly, so the owning order is resolved first.
     */
    Fulfillment retryFulfillment(UUID fulfillmentId);

    Optional<Fulfillment> findByOrderId(UUID orderId);

    List<FulfillmentAttempt> findAttempts(UUID orderId);

    /**
     * @return the delivery status and attempt history for an order
     */
    FulfillmentResponse findResponseByOrderId(UUID orderId);

    /**
     * @return the delivery status and attempt history for a fulfilment id
     */
    FulfillmentResponse findResponseById(UUID fulfillmentId);

    /**
     * @param status optional status filter
     * @return every fulfilment, newest first
     */
    List<FulfillmentResponse> findAllResponses(FulfillmentStatus status);
}
