package org.example.gametopupkh.payment.reconciliation;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentReconciliationJobTests {

    @Test
    void delegatesEachRunToTheReconciliationService() {
        PaymentReconciliationService service = mock(PaymentReconciliationService.class);
        when(service.reconcile()).thenReturn(emptyResult());

        new PaymentReconciliationJob(service).reconcilePayments();

        verify(service).reconcile();
    }

    @Test
    void aFailedRunDoesNotEscapeTheScheduledEntryPoint() {
        PaymentReconciliationService service = mock(PaymentReconciliationService.class);
        when(service.reconcile()).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> new PaymentReconciliationJob(service).reconcilePayments())
                .doesNotThrowAnyException();
        verify(service).reconcile();
    }

    private PaymentReconciliationResult emptyResult() {
        Instant now = Instant.now();
        return new PaymentReconciliationResult(now, now, 0, 0, 0, 0, 0, 0, 0, List.of());
    }
}
