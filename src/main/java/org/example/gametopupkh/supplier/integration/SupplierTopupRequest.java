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
        int quantity) {
}
