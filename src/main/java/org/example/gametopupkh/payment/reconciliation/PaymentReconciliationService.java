package org.example.gametopupkh.payment.reconciliation;

/**
 * Batch reconciliation of payments that have not reached a final state. Only ABA
 * payments are inspected because the check-transaction API is the authoritative
 * source for their status; other gateways return an empty result.
 */
public interface PaymentReconciliationService {

    /**
     * Inspects a bounded batch of unresolved payments, asks the provider for the
     * authoritative status, and applies verified outcomes transactionally.
     *
     * @return a summary of the run, including per-payment outcomes
     */
    PaymentReconciliationResult reconcile();
}
