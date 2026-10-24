package org.example.gametopupkh.supplier.service;

import java.util.UUID;

import org.example.gametopupkh.supplier.entity.SupplierProduct;

/** Selects catalog mappings without submitting or reserving a delivery. */
public interface SupplierSelectionService {
    /**
     * Selects the lowest-cost active mapping from an ACTIVE supplier for an active package.
     * Only the requested currency is compared; equal costs use creation time then ID.
     *
     * @param packageId the catalog package to supply
     * @param currency the supplier cost currency, exactly three uppercase letters
     * @return the selected mapping; eligibility is evaluated at query time, not reserved
     * @throws org.example.gametopupkh.common.exception.ApiException if arguments are invalid,
     *         the package is missing or inactive, or no eligible mapping exists
     */
    SupplierProduct selectForPackage(UUID packageId, String currency);
}
