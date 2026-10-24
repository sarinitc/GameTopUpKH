package org.example.gametopupkh.fulfillment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.supplier.SupplierTestFixtures;
import org.example.gametopupkh.supplier.entity.SupplierProduct;
import org.example.gametopupkh.supplier.integration.SupplierClient;
import org.example.gametopupkh.supplier.integration.SupplierClientRegistry;
import org.example.gametopupkh.supplier.integration.SupplierTopupResponse;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.order.OrderStatusHistory;
import org.example.gametopupkh.order.OrderStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:fulfillment-service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=fulfillment-service-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock",
        "fulfillment.worker.enabled=false",
        "supplier.max-attempts=2"
})
class FulfillmentServiceTests {

    @Autowired private FulfillmentService service;
    @Autowired private FulfillmentRepository fulfillments;
    @Autowired private OrderRepository orders;
    @Autowired private OrderStatusHistoryRepository history;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private SupplierClient supplier;
    @MockitoBean private SupplierClientRegistry supplierClients;

    private Order order;
    private SupplierProduct selectedProduct;

    @BeforeEach
    void setUp() {
        when(supplierClients.getConfiguredClient()).thenReturn(supplier);
        when(supplierClients.getClient("mock")).thenReturn(supplier);
        when(supplier.key()).thenReturn("mock");
        when(supplier.name()).thenReturn("test-supplier");
        selectedProduct = SupplierTestFixtures.createEligibleProduct(entityManager, transactionManager);
        order = new Order();
        order.setOrderNumber("FUL-" + UUID.randomUUID());
        order.setPackageId(selectedProduct.getPackageId());
        order.setPlayerId("player-1");
        order.setGameNameSnapshot("Game");
        order.setPackageNameSnapshot("Package");
        order.setUnitPrice(new BigDecimal("4.25"));
        order.setTotalAmount(new BigDecimal("4.25"));
        order.setCurrency("USD");
        order.setStatus(OrderStatus.PAID);
        order = orders.saveAndFlush(order);
    }

    @Test
    void paidOrderIsDeliveredAndCompleted() {
        when(supplier.fulfill(any()))
                .thenReturn(SupplierTopupResponse.success("SUP-1", "Delivered"));

        Fulfillment fulfillment = service.fulfillOrder(order.getId());

        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(fulfillment.getAttemptCount()).isEqualTo(1);
        assertThat(fulfillment.getSupplierReference()).isEqualTo("SUP-1");
        assertThat(fulfillment.getCompletedAt()).isNotNull();
        assertThat(fulfillment)
                .extracting("supplierId", "supplierProductId", "supplierSku", "supplierAdapterKey")
                .containsExactly(selectedProduct.getSupplierId(), selectedProduct.getId(),
                        selectedProduct.getExternalSku(), "mock");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);

        List<FulfillmentAttempt> attempts = service.findAttempts(order.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(FulfillmentAttempt.Status.SUCCESS);
        assertThat(attempts.get(0).getSupplierReference()).isEqualTo("SUP-1");
        verify(supplier, times(1)).fulfill(any());

        assertThat(history.findAllByOrderIdOrderByChangedAtAsc(order.getId()))
                .extracting(OrderStatusHistory::getStatus)
                .containsExactly(OrderStatus.PROCESSING, OrderStatus.COMPLETED);
    }

    @Test
    void fulfilmentIsIdempotentForOneOrder() {
        when(supplier.fulfill(any()))
                .thenReturn(SupplierTopupResponse.success("SUP-1", "Delivered"));

        service.fulfillOrder(order.getId());
        service.fulfillOrder(order.getId());

        assertThat(fulfillments.findByOrderId(order.getId()).orElseThrow().getAttemptCount())
                .isEqualTo(1);
        assertThat(service.findAttempts(order.getId())).hasSize(1);
        verify(supplier, times(1)).fulfill(any());
    }

    @Test
    void failedDeliveryCanBeRetriedAfterRequiringReview() {
        when(supplier.fulfill(any()))
                .thenReturn(SupplierTopupResponse.failure("Supplier is temporarily unavailable"))
                .thenReturn(SupplierTopupResponse.failure("Supplier is temporarily unavailable"))
                .thenReturn(SupplierTopupResponse.success("SUP-2", "Delivered"));

        Fulfillment failed = service.fulfillOrder(order.getId());

        assertThat(failed.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(2);
        assertThat(service.findAttempts(order.getId())).hasSize(2);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.NEEDS_REVIEW);

        Fulfillment retried = service.retry(order.getId());

        assertThat(retried.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(retried.getAttemptCount()).isEqualTo(3);
        assertThat(service.findAttempts(order.getId())).hasSize(3);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void unpaidOrderCannotBeFulfilled() {
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        orders.save(order);

        assertThatThrownBy(() -> service.fulfillOrder(order.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void unknownOrderCannotBeFulfilled() {
        assertThatThrownBy(() -> service.fulfillOrder(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "CANCELLED", "FAILED", "REFUNDED"})
    void pendingFulfillmentCannotDeliverAnUnpaidOrClosedOrder(OrderStatus status) {
        Fulfillment pending = new Fulfillment();
        pending.setOrderId(order.getId());
        fulfillments.saveAndFlush(pending);
        order.setStatus(status);
        orders.saveAndFlush(order);

        assertThatThrownBy(() -> service.fulfillOrder(order.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(supplier, never()).fulfill(any());
        assertThat(service.findAttempts(order.getId())).isEmpty();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(status);
    }

    @Test
    void supplierReferencesFitTheDatabaseColumns() {
        when(supplier.fulfill(any()))
                .thenReturn(SupplierTopupResponse.success("R".repeat(300), "Delivered"));

        Fulfillment fulfillment = service.fulfillOrder(order.getId());
        fulfillments.flush();

        assertThat(fulfillment.getSupplierReference()).hasSize(255);
        assertThat(service.findAttempts(order.getId()).get(0).getSupplierReference()).hasSize(255);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void retryClearsPreviousCompletionAndErrorBeforeCallingSupplier() {
        Fulfillment failed = new Fulfillment();
        failed.setOrderId(order.getId());
        failed.setStatus(FulfillmentStatus.FAILED);
        failed.setCompletedAt(Instant.now());
        failed.setLastError("Previous failure");
        fulfillments.saveAndFlush(failed);
        order.setStatus(OrderStatus.NEEDS_REVIEW);
        orders.saveAndFlush(order);
        when(supplier.fulfill(any())).thenAnswer(invocation -> {
            Fulfillment processing = fulfillments.findByOrderId(order.getId()).orElseThrow();
            assertThat(processing.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
            assertThat(processing.getCompletedAt()).isNull();
            assertThat(processing.getLastError()).isNull();
            return SupplierTopupResponse.success("SUP-RETRY", "Delivered");
        });

        assertThat(service.retry(order.getId()).getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
    }

    @Test
    void nullResultRemainsUnknownAndCannotBeResubmitted() {
        when(supplier.fulfill(any())).thenReturn(null);

        assertUnknownDeliveryCannotBeResubmitted();
    }

    @Test
    void supplierExceptionRemainsUnknownAndCannotBeResubmitted() {
        when(supplier.fulfill(any()))
                .thenThrow(new SupplierClient.SupplierException("test-supplier", "Transport error"));

        assertUnknownDeliveryCannotBeResubmitted();
    }

    @Test
    void unexpectedExceptionRemainsUnknownAndCannotBeResubmitted() {
        when(supplier.fulfill(any())).thenThrow(new IllegalStateException("Transport error"));

        assertUnknownDeliveryCannotBeResubmitted();
    }

    private void assertUnknownDeliveryCannotBeResubmitted() {
        Fulfillment unknown = service.fulfillOrder(order.getId());
        Fulfillment repeated = service.fulfillOrder(order.getId());
        Fulfillment manualRetry = service.retry(order.getId());
        Fulfillment retryById = service.retryFulfillment(unknown.getId());

        assertThat(unknown.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
        assertThat(unknown.getAttemptCount()).isEqualTo(1);
        assertThat(unknown.getCompletedAt()).isNull();
        assertThat(unknown.getLastError()).isNotBlank();
        assertThat(repeated.getId()).isEqualTo(unknown.getId());
        assertThat(manualRetry.getId()).isEqualTo(unknown.getId());
        assertThat(retryById.getId()).isEqualTo(unknown.getId());
        assertThat(service.findAttempts(order.getId()))
                .extracting(FulfillmentAttempt::getStatus)
                .containsExactly(FulfillmentAttempt.Status.PENDING);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);
        verify(supplier, times(1)).fulfill(any());
    }
}
