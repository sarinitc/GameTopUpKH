package org.example.gametopupkh.supplier.integration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves outbound adapters using stable keys, never editable supplier names. */
@Component
public class SupplierClientRegistry {

    private final Map<String, SupplierClient> clients;
    private final SupplierClient configuredClient;

    public SupplierClientRegistry(List<SupplierClient> clients,
            @Value("${supplier.client:mock}") String configuredKey) {
        Map<String, SupplierClient> registered = new HashMap<>();
        for (SupplierClient client : clients) {
            String key = requireKey(client.key());
            if (registered.putIfAbsent(key, client) != null) {
                throw new IllegalArgumentException("Duplicate supplier adapter key: " + key);
            }
        }
        this.clients = Map.copyOf(registered);
        this.configuredClient = getClient(configuredKey);
    }

    public SupplierClient getConfiguredClient() {
        return configuredClient;
    }

    public SupplierClient getClient(String key) {
        String adapterKey = requireKey(key);
        SupplierClient client = clients.get(adapterKey);
        if (client == null) {
            throw new IllegalArgumentException("Unknown supplier adapter key: " + adapterKey);
        }
        return client;
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Supplier adapter key must not be blank");
        }
        return key;
    }
}
