package org.example.gametopupkh.supplier.dto.response;

import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.supplier.entity.Supplier;
import org.example.gametopupkh.supplier.enums.SupplierStatus;

public record SupplierResponse(UUID id, String name, SupplierStatus status, Instant createdAt, Instant updatedAt) {
    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(supplier.getId(), supplier.getName(), supplier.getStatus(),
                supplier.getCreatedAt(), supplier.getUpdatedAt());
    }
}
