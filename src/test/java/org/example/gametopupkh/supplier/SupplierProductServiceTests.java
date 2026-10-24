package org.example.gametopupkh.supplier;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.catalog.packagecatalog.repository.GamePackageRepository;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.supplier.dto.request.SupplierProductRequest;
import org.example.gametopupkh.supplier.entity.Supplier;
import org.example.gametopupkh.supplier.entity.SupplierProduct;
import org.example.gametopupkh.supplier.enums.SupplierStatus;
import org.example.gametopupkh.supplier.repository.SupplierProductRepository;
import org.example.gametopupkh.supplier.repository.SupplierRepository;
import org.example.gametopupkh.supplier.service.impl.SupplierProductServiceImpl;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierProductServiceTests {
    @Mock private SupplierProductRepository products;
    @Mock private SupplierRepository suppliers;
    @Mock private GamePackageRepository packages;
    private SupplierProductServiceImpl service;
    private final UUID supplierId = UUID.randomUUID();
    private final UUID packageId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() { service = new SupplierProductServiceImpl(products, suppliers, packages); }

    @ParameterizedTest
    @ValueSource(strings = {"uq_supplier_products_external_sku", "PUBLIC.UQ_SUPPLIER_PRODUCTS_EXTERNAL_SKU_INDEX_A"})
    void databaseDetectedCreateRaceBecomesConflict(String constraint) {
        eligibleParents();
        when(products.saveAndFlush(any())).thenThrow(integrity("23505", constraint));
        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("SUPPLIER_PRODUCT_SKU_EXISTS");
                });
    }

    @Test
    void databaseDetectedUpdateRaceBecomesConflict() {
        SupplierProduct product = new SupplierProduct();
        product.setId(productId); product.setSupplierId(supplierId); product.setPackageId(packageId);
        when(products.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(products.saveAndFlush(any())).thenThrow(integrity("23505", "uq_supplier_products_external_sku"));
        assertThatThrownBy(() -> service.update(productId, request()))
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"supplier_products_package_id_fkey", "other_unique_constraint", "uq_supplier_products_external_sku_other"})
    void unrelatedIntegrityFailuresArePreserved(String constraint) {
        eligibleParents();
        DataIntegrityViolationException failure = integrity("23505", constraint);
        when(products.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.create(request())).isSameAs(failure);
    }

    @Test
    void sameNamedNonUniqueFailureAndUnclassifiedIntegrityFailuresArePreserved() {
        eligibleParents();
        DataIntegrityViolationException failure = integrity("23514", "uq_supplier_products_external_sku");
        when(products.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.create(request())).isSameAs(failure);
        DataIntegrityViolationException unclassified = new DataIntegrityViolationException("Unknown integrity error");
        doThrow(unclassified).when(products).saveAndFlush(any());
        assertThatThrownBy(() -> service.create(request())).isSameAs(unclassified);
    }

    private SupplierProductRequest request() {
        return new SupplierProductRequest(supplierId, packageId, "Race-Sku", BigDecimal.ONE, null, null);
    }
    private void eligibleParents() {
        Supplier supplier = new Supplier(); supplier.setId(supplierId); supplier.setStatus(SupplierStatus.ACTIVE);
        GamePackage gamePackage = new GamePackage(); gamePackage.setId(packageId); gamePackage.setActive(true);
        when(suppliers.findByIdForUpdate(supplierId)).thenReturn(Optional.of(supplier));
        when(packages.findById(packageId)).thenReturn(Optional.of(gamePackage));
    }
    private DataIntegrityViolationException integrity(String sqlState, String constraint) {
        return new DataIntegrityViolationException("Database rejected write", new ConstraintViolationException(
                "Constraint violation", new SQLException("constraint violation", sqlState), constraint));
    }
}
