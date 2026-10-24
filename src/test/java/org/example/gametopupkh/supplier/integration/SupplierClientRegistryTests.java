package org.example.gametopupkh.supplier.integration;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierClientRegistryTests {

    @Test
    void defaultConfigurationDeliversThroughMock() {
        new ApplicationContextRunner()
                .withUserConfiguration(SupplierClientRegistry.class, MockSupplierClient.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var result = context.getBean(SupplierClientRegistry.class)
                            .getConfiguredClient().fulfill(request());
                    assertThat(result.success()).isTrue();
                    assertThat(result.supplierReference()).isEqualTo("MOCK-ORDER-123");
                });
    }

    @Test
    void configuredKeySelectsTheMatchingAdapter() {
        var registry = new SupplierClientRegistry(
                List.of(new MockSupplierClient(false), adapter("second", "SECOND-123")), "second");

        assertThat(registry.getConfiguredClient().fulfill(request()).supplierReference())
                .isEqualTo("SECOND-123");
        assertThat(registry.getClient("mock").fulfill(request()).supplierReference())
                .isEqualTo("MOCK-ORDER-123");
    }

    @Test
    void unknownConfiguredAdapterFailsInsteadOfFallingBackToMock() {
        assertThatThrownBy(() -> new SupplierClientRegistry(List.of(new MockSupplierClient(false)), "unknown"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown");
    }

    @Test
    void duplicateKeysAreRejected() {
        assertThatThrownBy(() -> new SupplierClientRegistry(
                List.of(new MockSupplierClient(false), adapter("mock", "WRONG")), "mock"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void displayNameIsNotAnAdapterKey() {
        var registry = new SupplierClientRegistry(List.of(new MockSupplierClient(false)), "mock");

        assertThatThrownBy(() -> registry.getClient("mock-supplier"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mock-supplier");
    }

    @Test
    void blankAdapterKeyIsRejected() {
        assertThatThrownBy(() -> new SupplierClientRegistry(List.of(adapter(" ", "WRONG")), "mock"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("key");
    }

    private SupplierClient adapter(String key, String reference) {
        return new SupplierClient() {
            @Override
            public String key() { return key; }

            @Override
            public SupplierTopupResponse fulfill(SupplierTopupRequest request) {
                return SupplierTopupResponse.success(reference, "Delivered");
            }
        };
    }

    private SupplierTopupRequest request() {
        return new SupplierTopupRequest(UUID.randomUUID(), "ORDER-123", UUID.randomUUID(),
                "Game", "60 Diamonds", "player-1", "zone-1", 1);
    }
}
