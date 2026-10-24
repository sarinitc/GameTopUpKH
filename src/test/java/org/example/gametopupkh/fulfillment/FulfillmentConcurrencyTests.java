package org.example.gametopupkh.fulfillment;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import org.example.gametopupkh.supplier.SupplierTestFixtures;
import org.example.gametopupkh.supplier.integration.SupplierClient;
import org.example.gametopupkh.supplier.integration.SupplierClientRegistry;
import org.example.gametopupkh.supplier.integration.SupplierTopupResponse;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:fulfillment-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=fulfillment-concurrency-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.max-attempts=2", "fulfillment.worker.enabled=false"
})
class FulfillmentConcurrencyTests {

    @Autowired private FulfillmentService service;
    @Autowired private FulfillmentRepository fulfillments;
    @Autowired private OrderRepository orders;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private SupplierClient supplier;
    @MockitoBean private SupplierClientRegistry supplierClients;

    @Test
    void concurrentInitialDeliveriesShareOneSupplierCall() throws Exception {
        Order order = createOrder(OrderStatus.PAID);
        assertConcurrentDelivery(order, () -> service.fulfillOrder(order.getId()));
    }

    @Test
    void concurrentManualRetriesShareOneSupplierCall() throws Exception {
        Order order = createOrder(OrderStatus.NEEDS_REVIEW);
        Fulfillment failed = new Fulfillment();
        failed.setOrderId(order.getId());
        failed.setStatus(FulfillmentStatus.FAILED);
        fulfillments.saveAndFlush(failed);
        assertConcurrentDelivery(order, () -> service.retry(order.getId()));
    }

    private void assertConcurrentDelivery(Order order, Supplier<Fulfillment> delivery) throws Exception {
        when(supplierClients.getConfiguredClient()).thenReturn(supplier);
        when(supplierClients.getClient("mock")).thenReturn(supplier);
        when(supplier.key()).thenReturn("mock");
        CountDownLatch supplierEntered = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        when(supplier.name()).thenReturn("test-supplier");
        when(supplier.fulfill(any())).thenAnswer(invocation -> {
            supplierEntered.countDown();
            if (!releaseSupplier.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish delivery");
            }
            return SupplierTopupResponse.success("SUP-ONCE", "Delivered");
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(delivery::get);
            assertThat(supplierEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // A separate reader sees the committed claim before the outbound call completes.
            Fulfillment claimed = fulfillments.findByOrderId(order.getId()).orElseThrow();
            assertThat(claimed.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
            assertThat(claimed.getAttemptCount()).isEqualTo(1);
            assertThat(claimed).extracting("supplierProductId").isNotNull();
            assertThat(service.findAttempts(order.getId()))
                    .extracting(FulfillmentAttempt::getStatus)
                    .containsExactly(FulfillmentAttempt.Status.PENDING);
            assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.PROCESSING);

            var second = executor.submit(delivery::get);
            Fulfillment secondResult = second.get(2, TimeUnit.SECONDS);
            assertThat(secondResult.getId()).isEqualTo(claimed.getId());
            assertThat(secondResult.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
            verify(supplier, times(1)).fulfill(any());
            releaseSupplier.countDown();

            Fulfillment firstResult = first.get(5, TimeUnit.SECONDS);
            assertThat(firstResult.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
            assertThat(secondResult.getId()).isEqualTo(firstResult.getId());
            assertThat(fulfillments.findByOrderId(order.getId()).orElseThrow().getStatus())
                    .isEqualTo(FulfillmentStatus.SUCCEEDED);
            assertThat(service.findAttempts(order.getId())).hasSize(1);
            assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.COMPLETED);
            verify(supplier, times(1)).fulfill(any());
        } finally {
            releaseSupplier.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Order createOrder(OrderStatus status) {
        Order order = new Order();
        order.setOrderNumber("CON-" + UUID.randomUUID());
        order.setPackageId(SupplierTestFixtures.createEligibleProduct(entityManager, transactionManager).getPackageId());
        order.setPlayerId("player-1");
        order.setGameNameSnapshot("Game");
        order.setPackageNameSnapshot("60 Diamonds");
        order.setUnitPrice(new BigDecimal("4.25"));
        order.setTotalAmount(new BigDecimal("4.25"));
        order.setCurrency("USD");
        order.setStatus(status);
        return orders.saveAndFlush(order);
    }
}
