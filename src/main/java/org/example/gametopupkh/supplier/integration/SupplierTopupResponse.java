package org.example.gametopupkh.supplier.integration;

public record SupplierTopupResponse(
        boolean success,
        String supplierReference,
        String message) {

    public static SupplierTopupResponse success(String supplierReference, String message) {
        return new SupplierTopupResponse(true, supplierReference, message);
    }

    public static SupplierTopupResponse failure(String message) {
        return new SupplierTopupResponse(false, null, message);
    }
}
