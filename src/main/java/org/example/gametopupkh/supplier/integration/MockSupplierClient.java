package org.example.gametopupkh.supplier.integration;

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
    public String key() {
        return "mock";
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SupplierTopupResponse fulfill(SupplierTopupRequest request) {
        if (fail) {
            return SupplierTopupResponse.failure("Mock supplier is configured to reject deliveries");
        }
        String reference = "MOCK-" + request.orderNumber();
        return SupplierTopupResponse.success(reference,
                "Mock top-up delivered for player " + request.playerId());
    }
}
