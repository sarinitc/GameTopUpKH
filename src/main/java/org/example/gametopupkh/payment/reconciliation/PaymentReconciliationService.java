package org.example.gametopupkh.payment.reconciliation;

/**
 * Batch reconciliation of payments that have not reached a final state. ABA
 * payments use the active gateway's check-transaction API; Bakong payments use
 * their own authoritative MD5 verification service and are reconciled even when
 * the storefront's default gateway is mock or ABA.
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
