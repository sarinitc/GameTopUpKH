package org.example.gametopupkh.supplier;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.example.gametopupkh.catalog.game.entity.Game;
import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.supplier.entity.Supplier;
import org.example.gametopupkh.supplier.entity.SupplierProduct;
import org.example.gametopupkh.supplier.enums.SupplierStatus;
import org.example.gametopupkh.supplier.service.SupplierSelectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:supplier-selection;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=supplier-selection-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.client=mock", "supplier.mock.fail=false",
        "fulfillment.worker.enabled=false", "payment.reconciliation.enabled=false"
})
@Transactional
class SupplierSelectionServiceTests {
    @Autowired private SupplierSelectionService selection;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    private GamePackage gamePackage;
    private Supplier supplier;

    @BeforeEach
    void setUp() {
        Game game = new Game();
        game.setName("Selection game");
        game.setSlug("selection-" + UUID.randomUUID());
        entityManager.persist(game);
        gamePackage = createPackage(game.getId());
        supplier = createSupplier(SupplierStatus.ACTIVE);
        entityManager.flush();
    }

    @Test
    void selectsLowestCostAcrossSuppliersAndMultipleSkus() {
        mapping(supplier, gamePackage, "5.00", "USD", true);
        Supplier alternative = createSupplier(SupplierStatus.ACTIVE);
        mapping(alternative, gamePackage, "3.00", "USD", true);
        UUID cheapest = mapping(alternative, gamePackage, "1.25", "USD", true);

        SupplierProduct selected = selection.selectForPackage(gamePackage.getId(), "USD");

        assertThat(selected.getId()).isEqualTo(cheapest);
        assertThat(selected.getSupplierId()).isEqualTo(alternative.getId());
        assertThat(selected.getCostPrice()).isEqualByComparingTo("1.25");
    }

    @Test
    void excludesDisabledMappings() {
        mapping(supplier, gamePackage, "0.01", "USD", false);
        UUID eligible = mapping(supplier, gamePackage, "2.00", "USD", true);

        assertThat(selection.selectForPackage(gamePackage.getId(), "USD").getId()).isEqualTo(eligible);
    }

    @ParameterizedTest
    @EnumSource(value = SupplierStatus.class, names = {"INACTIVE", "SUSPENDED"})
    void excludesSuppliersWhoseStatusChangedAfterMappingCreation(SupplierStatus status) {
        mapping(supplier, gamePackage, "0.01", "USD", true);
        supplier.setStatus(status);
        UUID eligible = mapping(createSupplier(SupplierStatus.ACTIVE), gamePackage, "2.00", "USD", true);

        assertThat(selection.selectForPackage(gamePackage.getId(), "USD").getId()).isEqualTo(eligible);
    }

    @Test
    void onlyComparesCostsInRequestedCurrency() {
        mapping(supplier, gamePackage, "0.01", "USD", true);
        UUID eligible = mapping(supplier, gamePackage, "4000.00", "KHR", true);

        assertThat(selection.selectForPackage(gamePackage.getId(), "KHR").getId()).isEqualTo(eligible);
    }

    @Test
    void excludesMappingsForOtherPackages() {
        mapping(supplier, createPackage(gamePackage.getGameId()), "0.01", "USD", true);
        UUID eligible = mapping(supplier, gamePackage, "2.00", "USD", true);

        assertThat(selection.selectForPackage(gamePackage.getId(), "USD").getId()).isEqualTo(eligible);
    }

    @Test
    void acceptsZeroCost() {
        mapping(supplier, gamePackage, "0.01", "USD", true);
        UUID free = mapping(supplier, gamePackage, "0.00", "USD", true);

        assertThat(selection.selectForPackage(gamePackage.getId(), "USD").getId()).isEqualTo(free);
    }

    @Test
    void equalCostPrefersOldestMapping() {
        UUID newer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID older = UUID.fromString("00000000-0000-0000-0000-000000000002");
        mapping(newer, supplier, gamePackage, "2.00", "USD", true, Instant.parse("2026-01-02T00:00:00Z"));
        mapping(older, supplier, gamePackage, "2.00", "USD", true, Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(selection.selectForPackage(gamePackage.getId(), "USD").getId()).isEqualTo(older);
    }

    @Test
    void equalCostAndCreationTimeUsesIdForStableSelection() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        mapping(second, supplier, gamePackage, "2.00", "USD", true, created);
        mapping(first, supplier, gamePackage, "2.00", "USD", true, created);

        assertThat(selection.selectForPackage(gamePackage.getId(), "USD").getId()).isEqualTo(first);
        entityManager.clear();
        assertThat(selection.selectForPackage(gamePackage.getId(), "USD").getId()).isEqualTo(first);
    }

    @Test
    void reportsNoEligibleSupplierWithoutFallingBackToAnotherCurrency() {
        mapping(supplier, gamePackage, "1.00", "KHR", true);
        mapping(supplier, gamePackage, "1.00", "USD", false);
        mapping(createSupplier(SupplierStatus.SUSPENDED), gamePackage, "1.00", "USD", true);

        assertSelectionError(gamePackage.getId(), "USD", HttpStatus.CONFLICT, "NO_SUPPLIER_AVAILABLE");
    }

    @Test
    void reportsNoSupplierWhenPackageHasNoMappings() {
        assertSelectionError(gamePackage.getId(), "USD", HttpStatus.CONFLICT, "NO_SUPPLIER_AVAILABLE");
    }

    @Test
    void rejectsMissingPackage() {
        assertSelectionError(UUID.randomUUID(), "USD", HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND");
    }

    @Test
    void rejectsPackageDisabledAfterMappingCreation() {
        mapping(supplier, gamePackage, "1.00", "USD", true);
        gamePackage.setActive(false);

        assertSelectionError(gamePackage.getId(), "USD", HttpStatus.CONFLICT, "PACKAGE_INACTIVE");
    }

    @Test
    void rejectsNullPackageId() {
        assertSelectionError(null, "USD", HttpStatus.BAD_REQUEST, "INVALID_SUPPLIER_SELECTION");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"usd", "US", "USDD", "USD ", "   ", "US1"})
    void rejectsMalformedCurrency(String currency) {
        assertSelectionError(gamePackage.getId(), currency, HttpStatus.BAD_REQUEST, "INVALID_SUPPLIER_SELECTION");
    }

    private void assertSelectionError(UUID packageId, String currency, HttpStatus status, String code) {
        assertThatThrownBy(() -> selection.selectForPackage(packageId, currency))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(status);
                    assertThat(exception.getCode()).isEqualTo(code);
                });
    }

    private GamePackage createPackage(UUID gameId) {
        GamePackage product = new GamePackage();
        product.setGameId(gameId);
        product.setName("Selection package");
        product.setSku(UUID.randomUUID().toString());
        product.setDiamondAmount(60);
        product.setPrice(new BigDecimal("10.00"));
        entityManager.persist(product);
        return product;
    }

    private Supplier createSupplier(SupplierStatus status) {
        Supplier parent = new Supplier();
        parent.setName("Selection supplier");
        parent.setStatus(status);
        entityManager.persist(parent);
        return parent;
    }

    private UUID mapping(Supplier parent, GamePackage product, String cost, String currency, boolean active) {
        UUID id = UUID.randomUUID();
        mapping(id, parent, product, cost, currency, active, Instant.now());
        return id;
    }

    private void mapping(UUID id, Supplier parent, GamePackage product, String cost, String currency,
            boolean active, Instant created) {
        entityManager.flush();
        jdbc.update("insert into supplier_products "
                        + "(id, supplier_id, package_id, external_sku, cost_price, currency, is_active, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, parent.getId(), product.getId(), "sku-" + id, new BigDecimal(cost), currency, active,
                Timestamp.from(created), Timestamp.from(created));
    }
}
