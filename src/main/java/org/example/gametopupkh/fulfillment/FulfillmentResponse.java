package org.example.gametopupkh.fulfillment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable delivery status and attempt history, independent of persistence entities. */
public record FulfillmentResponse(
        UUID fulfillmentId,
        UUID orderId,
        FulfillmentStatus status,
        int attemptCount,
        String supplierReference,
        String lastError,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        List<AttemptResponse> attempts) {

    public FulfillmentResponse {
        attempts = List.copyOf(attempts);
    }

    public static FulfillmentResponse from(Fulfillment fulfillment, List<FulfillmentAttempt> attempts) {
        return new FulfillmentResponse(
                fulfillment.getId(), fulfillment.getOrderId(), fulfillment.getStatus(),
                fulfillment.getAttemptCount(), fulfillment.getSupplierReference(), fulfillment.getLastError(),
                fulfillment.getStartedAt(), fulfillment.getCompletedAt(),
                fulfillment.getCreatedAt(), fulfillment.getUpdatedAt(),
                attempts.stream().map(AttemptResponse::from).toList());
    }

    public record AttemptResponse(
            UUID attemptId,
            int attemptNumber,
            FulfillmentAttempt.Status status,
            String supplierReference,
            String message,
            Instant createdAt) {

        public static AttemptResponse from(FulfillmentAttempt attempt) {
            return new AttemptResponse(attempt.getId(), attempt.getAttemptNumber(), attempt.getStatus(),
                    attempt.getSupplierReference(), attempt.getMessage(), attempt.getCreatedAt());
        }
    }
}
