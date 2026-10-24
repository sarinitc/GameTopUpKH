package org.example.gametopupkh.supplier.integration;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockSupplierClientTests {

    @Test
    void repeatedDeliveryUsesTheSameReferenceEvenAcrossClientInstances() {
        SupplierTopupRequest request = request("ORDER-123");

        var first = new MockSupplierClient(false).fulfill(request);
        var repeated = new MockSupplierClient(false).fulfill(request);

        assertThat(first.success()).isTrue();
        assertThat(first.supplierReference()).isNotBlank();
        assertThat(repeated.supplierReference()).isEqualTo(first.supplierReference());
        assertThat(new MockSupplierClient(false).fulfill(request("ORDER-456")).supplierReference())
                .isNotEqualTo(first.supplierReference());
    }

    @Test
    void configuredFailureDoesNotReturnASuccessReference() {
        var result = new MockSupplierClient(true).fulfill(request("ORDER-123"));

        assertThat(result.success()).isFalse();
        assertThat(result.supplierReference()).isNull();
        assertThat(result.message()).isNotBlank();
    }

    private SupplierTopupRequest request(String orderNumber) {
        return new SupplierTopupRequest(UUID.randomUUID(), orderNumber, UUID.randomUUID(),
                "Game", "60 Diamonds", "player-1", "zone-1", 1);
    }
}
