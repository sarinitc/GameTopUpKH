package org.example.gametopupkh.supplier.service.impl;

import java.util.UUID;

import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.catalog.packagecatalog.repository.GamePackageRepository;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.supplier.entity.SupplierProduct;
import org.example.gametopupkh.supplier.enums.SupplierStatus;
import org.example.gametopupkh.supplier.repository.SupplierProductRepository;
import org.example.gametopupkh.supplier.service.SupplierSelectionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SupplierSelectionServiceImpl implements SupplierSelectionService {
    private final SupplierProductRepository products;
    private final GamePackageRepository packages;

    public SupplierSelectionServiceImpl(SupplierProductRepository products, GamePackageRepository packages) {
        this.products = products;
        this.packages = packages;
    }

    @Override
    public SupplierProduct selectForPackage(UUID packageId, String currency) {
        if (packageId == null || currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SUPPLIER_SELECTION",
                    "Package ID and a three-letter uppercase currency are required");
        }

        GamePackage gamePackage = packages.findById(packageId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND",
                        "Package " + packageId + " was not found"));
        if (!gamePackage.isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "PACKAGE_INACTIVE",
                    "Package must be active to select a supplier");
        }

        return products.findEligibleForSelection(packageId, currency, SupplierStatus.ACTIVE, PageRequest.of(0, 1))
                .stream().findFirst().orElseThrow(() ->
                        new ApiException(HttpStatus.CONFLICT, "NO_SUPPLIER_AVAILABLE",
                                "No active supplier mapping is available for package " + packageId
                                        + " in " + currency));
    }
}
