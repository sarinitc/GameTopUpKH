package org.example.gametopupkh.supplier.service.impl;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.catalog.packagecatalog.repository.GamePackageRepository;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.supplier.dto.request.SupplierProductRequest;
import org.example.gametopupkh.supplier.dto.response.SupplierProductResponse;
import org.example.gametopupkh.supplier.entity.Supplier;
import org.example.gametopupkh.supplier.entity.SupplierProduct;
import org.example.gametopupkh.supplier.repository.SupplierProductRepository;
import org.example.gametopupkh.supplier.repository.SupplierRepository;
import org.example.gametopupkh.supplier.service.SupplierProductService;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SupplierProductServiceImpl implements SupplierProductService {
    private static final String SKU_CONSTRAINT = "uq_supplier_products_external_sku";
    private final SupplierProductRepository products;
    private final SupplierRepository suppliers;
    private final GamePackageRepository packages;

    public SupplierProductServiceImpl(SupplierProductRepository products, SupplierRepository suppliers,
            GamePackageRepository packages) {
        this.products = products;
        this.suppliers = suppliers;
        this.packages = packages;
    }

    @Override
    public SupplierProductResponse create(SupplierProductRequest request) {
        requireEligibleParents(request.supplierId(), request.packageId());
        String sku = request.externalSku().trim();
        if (products.existsBySupplierIdAndExternalSku(request.supplierId(), sku)) throw duplicateSku();
        SupplierProduct product = new SupplierProduct();
        product.setSupplierId(request.supplierId());
        product.setPackageId(request.packageId());
        product.setExternalSku(sku);
        product.setCostPrice(request.costPrice());
        product.setCurrency(request.currency() == null ? "USD" : request.currency());
        product.setActive(request.active() == null || request.active());
        return persist(product);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierProductResponse> findAll(UUID supplierId, UUID packageId, Boolean active) {
        return products.findFiltered(supplierId, packageId, active).stream().map(SupplierProductResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierProductResponse findById(UUID id) {
        return SupplierProductResponse.from(products.findById(id).orElseThrow(() -> missing(id)));
    }

    @Override
    public SupplierProductResponse update(UUID id, SupplierProductRequest request) {
        SupplierProduct product = locked(id);
        if ((request.supplierId() != null && !request.supplierId().equals(product.getSupplierId()))
                || (request.packageId() != null && !request.packageId().equals(product.getPackageId()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SUPPLIER_PRODUCT_PARENTS_IMMUTABLE",
                    "Supplier and package cannot be changed after creation");
        }
        if (request.active() != null && request.active() != product.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SUPPLIER_PRODUCT_STATUS_SEPARATE",
                    "Use the status endpoint to enable or disable a mapping");
        }
        String sku = request.externalSku().trim();
        if (products.existsBySupplierIdAndExternalSkuAndIdNot(product.getSupplierId(), sku, id)) throw duplicateSku();
        product.setExternalSku(sku);
        product.setCostPrice(request.costPrice());
        if (request.currency() != null) product.setCurrency(request.currency());
        return persist(product);
    }

    @Override
    public SupplierProductResponse updateStatus(UUID id, SupplierProductRequest request) {
        SupplierProduct product = locked(id);
        if (Boolean.TRUE.equals(request.active())) requireEligibleParents(product.getSupplierId(), product.getPackageId());
        product.setActive(Boolean.TRUE.equals(request.active()));
        return persist(product);
    }

    private void requireEligibleParents(UUID supplierId, UUID packageId) {
        // Share this lock with supplier status changes so eligibility cannot change during create/enable.
        Supplier supplier = suppliers.findByIdForUpdate(supplierId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Supplier " + supplierId + " was not found"));
        GamePackage gamePackage = packages.findById(packageId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND", "Package " + packageId + " was not found"));
        if (!supplier.getStatus().isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "SUPPLIER_INACTIVE", "Supplier must be ACTIVE to create or enable a mapping");
        }
        if (!gamePackage.isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "PACKAGE_INACTIVE", "Package must be active to create or enable a mapping");
        }
    }

    private SupplierProductResponse persist(SupplierProduct product) {
        try {
            return SupplierProductResponse.from(products.saveAndFlush(product));
        } catch (DataIntegrityViolationException exception) {
            // Only this unique constraint denotes a duplicate SKU; preserve unrelated integrity failures.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation && "23505".equals(violation.getSQLState())
                        && isSkuConstraint(violation.getConstraintName())) throw duplicateSku();
            }
            throw exception;
        }
    }

    private boolean isSkuConstraint(String name) {
        if (name == null) return false;
        String normalized = name.toLowerCase(Locale.ROOT).replace("\"", "");
        // H2 reports a schema-qualified backing index; PostgreSQL reports the constraint name.
        normalized = normalized.split("\\s", 2)[0];
        normalized = normalized.substring(normalized.lastIndexOf('.') + 1);
        return normalized.equals(SKU_CONSTRAINT) || normalized.matches(SKU_CONSTRAINT + "_index_[a-z0-9]+");
    }
    private SupplierProduct locked(UUID id) { return products.findByIdForUpdate(id).orElseThrow(() -> missing(id)); }
    private ApiException missing(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "SUPPLIER_PRODUCT_NOT_FOUND", "Supplier product " + id + " was not found");
    }
    private ApiException duplicateSku() {
        return new ApiException(HttpStatus.CONFLICT, "SUPPLIER_PRODUCT_SKU_EXISTS", "This supplier already has a mapping for that external SKU");
    }
}
