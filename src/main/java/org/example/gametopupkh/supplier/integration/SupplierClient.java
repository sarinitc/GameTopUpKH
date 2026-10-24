package org.example.gametopupkh.supplier.integration;

/**
 * Outbound adapter used to deliver a paid top-up to a supplier.
 * <p>
 * Implementations are selected with the {@code supplier.client} property. The
 * request uses the order number as a stable supplier-side idempotency key.
 */
public interface SupplierClient {

    /** Stable adapter key used by {@code supplier.client}, independent of display names. */
    String key();

    /**
     * Short supplier name used in logs and the order status history.
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Delivers the top-up. A rejected delivery is returned as an unsuccessful
     * result; transport and protocol failures raise {@link SupplierException}.
     */
    SupplierTopupResponse fulfill(SupplierTopupRequest request);

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
