package org.example.gametopupkh.payment.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled entry point for payment reconciliation. Each run is bounded and
 * individual payment failures are isolated by the service, so one bad record
 * cannot stop the rest of the batch or the next scheduled run.
 */
@Component
@ConditionalOnProperty(name = "payment.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    private final PaymentReconciliationService reconciliationService;

    public PaymentReconciliationJob(PaymentReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelayString = "${payment.reconciliation.fixed-delay:PT1M}",
            initialDelayString = "${payment.reconciliation.initial-delay:PT30S}")
    public void reconcilePayments() {
        try {
            PaymentReconciliationResult result = reconciliationService.reconcile();
            if (result.hasErrors()) {
                log.warn("Payment reconciliation finished with {} error(s) out of {} payment(s)",
                        result.errors(), result.inspected());
            }
        } catch (RuntimeException e) {
            log.error("Payment reconciliation run failed; the next scheduled run will retry", e);
        }
    }
}
