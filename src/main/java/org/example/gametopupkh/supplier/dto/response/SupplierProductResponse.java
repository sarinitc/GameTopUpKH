package org.example.gametopupkh.supplier.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.supplier.entity.SupplierProduct;

public record SupplierProductResponse(UUID id, UUID supplierId, UUID packageId, String externalSku,
        BigDecimal costPrice, String currency, boolean active, Instant createdAt, Instant updatedAt) {
    public static SupplierProductResponse from(SupplierProduct product) {
        return new SupplierProductResponse(product.getId(), product.getSupplierId(), product.getPackageId(),
                product.getExternalSku(), product.getCostPrice(), product.getCurrency(), product.isActive(),
                product.getCreatedAt(), product.getUpdatedAt());
    }
}
