package org.example.gametopupkh.fulfillment;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.supplier.SupplierTestFixtures;
import org.example.gametopupkh.supplier.entity.Supplier;
import org.example.gametopupkh.supplier.entity.SupplierProduct;
import org.example.gametopupkh.supplier.enums.SupplierStatus;
import org.example.gametopupkh.supplier.integration.MockSupplierClient;
import org.example.gametopupkh.supplier.integration.SupplierTopupResponse;
import org.example.gametopupkh.supplier.repository.SupplierProductRepository;
import org.example.gametopupkh.supplier.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:selection-fulfillment;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=selection-fulfillment-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.client=mock", "supplier.mock.fail=false",
        "supplier.max-attempts=1", "fulfillment.worker.enabled=false",
        "payment.reconciliation.enabled=false"
})
class SupplierSelectionFulfillmentTests {
    @Autowired private FulfillmentService service;
    @Autowired private FulfillmentRepository fulfillments;
    @Autowired private OrderRepository orders;
    @Autowired private SupplierRepository suppliers;
    @Autowired private SupplierProductRepository products;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private MockSupplierClient client;
    @MockitoSpyBean private FulfillmentAttemptRepository attempts;
    private SupplierProduct mapping;
    private Order order;

    @BeforeEach
    void setUp() {
        mapping = SupplierTestFixtures.createEligibleProduct(entityManager, transactions);
        order = new Order();
        order.setOrderNumber("SEL-" + UUID.randomUUID());
        order.setPackageId(mapping.getPackageId());
        order.setPlayerId("player-selection");
        order.setGameNameSnapshot("Selection game");
        order.setPackageNameSnapshot("60 Diamonds");
        order.setUnitPrice(new BigDecimal("4.25"));
        order.setTotalAmount(new BigDecimal("4.25"));
        order.setCurrency("USD");
        order.setStatus(OrderStatus.PAID);
        order = orders.saveAndFlush(order);
    }

    @ParameterizedTest
    @EnumSource(value = SupplierStatus.class, names = {"INACTIVE", "SUSPENDED"})
    void inactiveSupplierCannotDeliver(SupplierStatus status) {
        var supplier = suppliers.findById(mapping.getSupplierId()).orElseThrow();
        supplier.setStatus(status);
        suppliers.saveAndFlush(supplier);
        assertNoDelivery();
    }

    @Test
    void inactiveMappingCannotDeliver() {
        mapping.setActive(false);
        products.saveAndFlush(mapping);
        assertNoDelivery();
    }

    @Test
    void missingMappingRequiresReviewAndExplicitRetryCanRecover() {
        products.delete(mapping);
        Fulfillment failed = assertNoDelivery();
        mapping.setId(null);
        mapping = products.saveAndFlush(mapping);
        Fulfillment recovered = service.retry(order.getId());
        assertThat(recovered.getId()).isEqualTo(failed.getId());
        assertThat(recovered.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(service.findAttempts(order.getId())).hasSize(1);
    }

    @Test
    void successfulMockDeliveryStoresSelectionAndCompletesOnlyOnce() {
        Fulfillment first = service.fulfillOrder(order.getId());
        Fulfillment duplicate = service.fulfillOrder(order.getId());
        assertThat(first.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(fulfillments.findByOrderId(order.getId()).orElseThrow())
                .extracting("supplierId", "supplierProductId", "supplierSku", "supplierAdapterKey")
                .containsExactly(mapping.getSupplierId(), mapping.getId(), mapping.getExternalSku(), "mock");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(service.findAttempts(order.getId())).hasSize(1);
        verify(client, times(1)).fulfill(any());
    }

    @Test
    void retryKeepsOriginalSupplierAndSkuAfterCatalogChanges() {
        String originalSku = mapping.getExternalSku();
        doReturn(SupplierTopupResponse.failure("Definitively rejected"))
                .when(client).fulfill(any());
        Fulfillment failed = service.fulfillOrder(order.getId());
        mapping.setExternalSku("EDITED-SKU");
        mapping.setCostPrice(new BigDecimal("99.00"));
        products.saveAndFlush(mapping);
        SupplierProduct cheaper = new SupplierProduct();
        cheaper.setPackageId(mapping.getPackageId());
        cheaper.setSupplierId(mapping.getSupplierId());
        cheaper.setExternalSku("CHEAPER-SKU");
        cheaper.setCostPrice(BigDecimal.ZERO);
        products.saveAndFlush(cheaper);
        doCallRealMethod().when(client).fulfill(any());

        Fulfillment retried = service.retry(order.getId());

        assertThat(retried.getId()).isEqualTo(failed.getId());
        assertThat(retried.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(retried).extracting("supplierProductId", "supplierSku")
                .containsExactly(mapping.getId(), originalSku);
        var requests = org.mockito.ArgumentCaptor.forClass(
                org.example.gametopupkh.supplier.integration.SupplierTopupRequest.class);
        verify(client, times(2)).fulfill(requests.capture());
        assertThat(requests.getAllValues()).extracting("supplierProductId", "externalSku")
                .containsOnly(org.assertj.core.groups.Tuple.tuple(mapping.getId(), originalSku));
    }

    @Test
    void selectedSupplierBecomingInactiveDoesNotRerouteRetry() {
        doReturn(SupplierTopupResponse.failure("Definitively rejected")).when(client).fulfill(any());
        Fulfillment failed = service.fulfillOrder(order.getId());
        var supplier = suppliers.findById(mapping.getSupplierId()).orElseThrow();
        supplier.setStatus(SupplierStatus.INACTIVE);
        suppliers.saveAndFlush(supplier);

        Fulfillment retried = service.retry(order.getId());

        assertThat(retried.getId()).isEqualTo(failed.getId());
        assertThat(retried.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(retried).extracting("supplierProductId").isEqualTo(mapping.getId());
        verify(client, times(1)).fulfill(any());
    }

    @Test
    void missingSuccessReferenceIsUnknownAndNeverResubmitted() {
        doReturn(SupplierTopupResponse.success(" ", "Malformed confirmation")).when(client).fulfill(any());
        Fulfillment unresolved = service.fulfillOrder(order.getId());
        service.fulfillOrder(order.getId());
        service.retry(order.getId());
        assertThat(unresolved.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
        assertThat(service.findAttempts(order.getId())).singleElement()
                .extracting(FulfillmentAttempt::getStatus).isEqualTo(FulfillmentAttempt.Status.PENDING);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);
        verify(client, times(1)).fulfill(any());
    }

    @Test
    void failedResultCommitLeavesDurableClaimAndNeverResubmits() {
        doAnswer(invocation -> {
            FulfillmentAttempt attempt = invocation.getArgument(0);
            if (attempt.getStatus() == FulfillmentAttempt.Status.SUCCESS) {
                throw new IllegalStateException("Simulated result storage failure");
            }
            return invocation.callRealMethod();
        }).when(attempts).save(any());
        assertThatThrownBy(() -> service.fulfillOrder(order.getId()))
                .isInstanceOf(IllegalStateException.class);

        Fulfillment claimed = fulfillments.findByOrderId(order.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
        assertThat(claimed.getAttemptCount()).isEqualTo(1);
        service.fulfillOrder(order.getId());
        service.retry(order.getId());
        assertThat(service.findAttempts(order.getId())).singleElement()
                .extracting(FulfillmentAttempt::getStatus).isEqualTo(FulfillmentAttempt.Status.PENDING);
        verify(client, times(1)).fulfill(any());
    }

    private Fulfillment assertNoDelivery() {
        Fulfillment failed = service.fulfillOrder(order.getId());
        assertThat(failed.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(failed.getAttemptCount()).isZero();
        assertThat(failed.getLastError()).contains("NO_SUPPLIER_AVAILABLE");
        assertThat(service.findAttempts(order.getId())).isEmpty();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);
        assertThat(service.fulfillOrder(order.getId()).getId()).isEqualTo(failed.getId());
        verify(client, never()).fulfill(any());
        return failed;
    }
}
