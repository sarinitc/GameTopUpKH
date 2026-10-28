package org.example.gametopupkh.supplier.integration;

import java.util.UUID;

public record SupplierTopupRequest(
        UUID orderId,
        String orderNumber,
        UUID packageId,
        String gameName,
        String packageName,
        String playerId,
        String zoneId,
        int quantity,
        UUID supplierProductId,
        String externalSku) {

    public SupplierTopupRequest(UUID orderId, String orderNumber, UUID packageId, String gameName,
            String packageName, String playerId, String zoneId, int quantity) {
        this(orderId, orderNumber, packageId, gameName, packageName, playerId, zoneId, quantity, null, null);
    }
}
