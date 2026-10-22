package org.example.gametopupkh.fulfillment.supplier;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Supplier used for local development and tests. It accepts every top-up by
 * default; set {@code supplier.mock.fail=true} to exercise the failure and retry
 * path.
 */
@Component
@ConditionalOnProperty(name = "supplier.client", havingValue = "mock", matchIfMissing = true)
public class MockSupplierClient implements SupplierClient {

    public static final String NAME = "mock-supplier";

    private final boolean fail;

    public MockSupplierClient(@Value("${supplier.mock.fail:false}") boolean fail) {
        this.fail = fail;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SupplierResult fulfill(SupplierRequest request) {
        if (fail) {
            return SupplierResult.failure("Mock supplier is configured to reject deliveries");
        }
        String reference = "MOCK-" + request.orderNumber() + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return SupplierResult.success(reference,
                "Mock top-up delivered for player " + request.playerId());
    }
}
