package org.example.gametopupkh.supplier.enums;

/**
 * Lifecycle state of an approved supplier. Only {@link #ACTIVE} suppliers may be
 * used for new package mappings or deliveries.
 */
public enum SupplierStatus {

    ACTIVE,
    INACTIVE,
    SUSPENDED;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
