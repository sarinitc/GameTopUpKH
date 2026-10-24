package org.example.gametopupkh.fulfillment;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** Recovers paid orders whose delivery has not started, without retrying failed deliveries. */
@Component
@ConditionalOnProperty(name = "fulfillment.worker.enabled", havingValue = "true", matchIfMissing = true)
public class FulfillmentWorker {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentWorker.class);

    private final FulfillmentRepository fulfillmentRepository;
    private final FulfillmentService fulfillmentService;
    private final int batchSize;

    public FulfillmentWorker(FulfillmentRepository fulfillmentRepository,
            FulfillmentService fulfillmentService,
            @Value("${fulfillment.worker.batch-size:50}") int batchSize) {
        Assert.isTrue(batchSize > 0, "fulfillment.worker.batch-size must be positive");
        this.fulfillmentRepository = fulfillmentRepository;
        this.fulfillmentService = fulfillmentService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${fulfillment.worker.fixed-delay:PT30S}",
            initialDelayString = "${fulfillment.worker.initial-delay:PT30S}")
    public void processPendingFulfillments() {
        // Each service call owns its transaction and rechecks state under an order
        // lock. One failure must not roll back the rest of the batch.
        for (UUID orderId : fulfillmentRepository.findPendingOrderIds(PageRequest.of(0, batchSize))) {
            try {
                fulfillmentService.fulfillOrder(orderId);
            } catch (RuntimeException exception) {
                log.warn("Could not recover fulfillment for order {}", orderId, exception);
            }
        }
    }
}
