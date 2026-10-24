package org.example.gametopupkh.fulfillment;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.gametopupkh.common.response.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local-development helper for exercising mock supplier delivery. The controller is
 * only registered while the mock supplier is active.
 */
@RestController
@ConditionalOnProperty(name = "supplier.client", havingValue = "mock", matchIfMissing = true)
@Tag(name = "Mock fulfillment", description = "Process orders while supplier.client=mock")
public class DevFulfillmentController {

    private final FulfillmentService fulfillmentService;

    public DevFulfillmentController(FulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @PostMapping("/api/v1/dev/fulfillments/orders/{orderId}/process")
    @Operation(summary = "Process a mock supplier fulfillment",
            description = "Starts or resumes fulfillment for a paid order.")
    public ResponseEntity<ApiResponse<FulfillmentResponse>> process(@PathVariable UUID orderId) {
        fulfillmentService.fulfillOrder(orderId);
        return ResponseEntity.ok(ApiResponse.ok(fulfillmentService.findResponseByOrderId(orderId)));
    }
}
