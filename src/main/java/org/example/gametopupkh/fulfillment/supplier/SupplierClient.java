package org.example.gametopupkh.fulfillment.supplier;

import java.util.UUID;

/**
 * Outbound adapter used to deliver a paid top-up to a supplier.
 * <p>
 * Implementations are selected with the {@code supplier.client} property. The
 * request uses the order number as a stable supplier-side idempotency key.
 */
public interface SupplierClient {

    /**
     * Short supplier name persisted in the attempt audit.
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Delivers the top-up. A rejected delivery is returned as an unsuccessful
     * result; transport and protocol failures raise {@link SupplierException}.
     */
    SupplierResult fulfill(SupplierRequest request);

    record SupplierRequest(
            UUID orderId,
            String orderNumber,
            UUID packageId,
            String gameName,
            String packageName,
            String playerId,
            String zoneId,
            int quantity) {
    }

    record SupplierResult(
            boolean success,
            String supplierReference,
            String message) {

        public static SupplierResult success(String supplierReference, String message) {
            return new SupplierResult(true, supplierReference, message);
        }

        public static SupplierResult failure(String message) {
            return new SupplierResult(false, null, message);
        }
    }

    /** Raised when the supplier cannot be reached or returns an unusable response. */
    class SupplierException extends RuntimeException {

        private final String supplier;

        public SupplierException(String supplier, String message) {
            super(message);
            this.supplier = supplier;
        }

        public SupplierException(String supplier, String message, Throwable cause) {
            super(message, cause);
            this.supplier = supplier;
        }

        public String getSupplier() {
            return supplier;
        }
    }
}
