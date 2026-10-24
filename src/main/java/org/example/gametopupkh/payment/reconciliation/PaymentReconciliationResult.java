package org.example.gametopupkh.payment.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.example.gametopupkh.payment.PaymentStatus;

/**
 * Outcome of one scheduled reconciliation run. The per-payment entries are also
 * written to the append-only payment event trail; this object is the run summary
 * returned to the job for structured logging and monitoring.
 */
public record PaymentReconciliationResult(
        Instant startedAt,
        Instant finishedAt,
        int inspected,
        int completed,
        int pending,
        int needsReview,
        int closed,
        int skipped,
        int errors,
        List<PaymentOutcome> outcomes) {

    public PaymentReconciliationResult {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    public boolean hasErrors() {
        return errors > 0;
    }

    public enum Outcome {
        COMPLETED,
        PENDING,
        NEEDS_REVIEW,
        CLOSED,
        SKIPPED,
        ERROR
    }

    /**
     * @param status the status read back after the reconciliation attempt, or
     *               {@code null} when the provider could not be consulted
     * @param detail a short, non-secret explanation for logs
     */
    public record PaymentOutcome(
            UUID paymentId,
            Outcome outcome,
            PaymentStatus status,
            String detail) {
    }
}
