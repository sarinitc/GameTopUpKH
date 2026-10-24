package org.example.gametopupkh.fulfillment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.example.gametopupkh.supplier.SupplierTestFixtures;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.order.OrderStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:fulfillment-worker;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=fulfillment-worker-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.client=mock", "supplier.mock.fail=false",
        "fulfillment.worker.enabled=true", "fulfillment.worker.initial-delay=PT24H",
        "fulfillment.worker.batch-size=50"
})
class FulfillmentWorkerTests {

    @Autowired private FulfillmentWorker worker;
    @Autowired private FulfillmentRepository fulfillments;
    @Autowired private FulfillmentAttemptRepository attempts;
    @Autowired private OrderRepository orders;
    @Autowired private OrderStatusHistoryRepository history;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private FulfillmentService service;

    @BeforeEach
    void clearPreviousOrders() {
        attempts.deleteAll();
        fulfillments.deleteAll();
        history.deleteAll();
        orders.deleteAll();
    }

    @Test
    void paidOrderWithoutFulfillmentIsRecoveredOnlyOnce() {
        Order order = createOrder(OrderStatus.PAID);

        worker.processPendingFulfillments();
        worker.processPendingFulfillments();

        assertCompleted(order);
        assertThat(service.findAttempts(order.getId())).hasSize(1);
    }

    @Test
    void pendingDeliveriesResumeOnlyForEligibleOrders() {
        Order paid = createOrder(OrderStatus.PAID);
        Order processing = createOrder(OrderStatus.PROCESSING);
        createFulfillment(paid, FulfillmentStatus.PENDING);
        createFulfillment(processing, FulfillmentStatus.PENDING);
        for (OrderStatus status : new OrderStatus[] {OrderStatus.PENDING_PAYMENT,
                OrderStatus.CANCELLED, OrderStatus.REFUNDED, OrderStatus.FAILED,
                OrderStatus.NEEDS_REVIEW, OrderStatus.COMPLETED}) {
            createFulfillment(createOrder(status), FulfillmentStatus.PENDING);
        }

        worker.processPendingFulfillments();

        assertCompleted(paid);
        assertCompleted(processing);
        assertThat(attempts.count()).isEqualTo(2);
        assertThat(fulfillments.findAllByStatusOrderByCreatedAtAsc(FulfillmentStatus.PENDING)).hasSize(6);
    }

    @Test
    void failedSuccessfulAndInProgressFulfillmentsAreNotResubmitted() {
        for (FulfillmentStatus status : new FulfillmentStatus[] {
                FulfillmentStatus.FAILED, FulfillmentStatus.SUCCEEDED, FulfillmentStatus.PROCESSING}) {
            createFulfillment(createOrder(OrderStatus.PAID), status);
        }

        worker.processPendingFulfillments();

        assertThat(attempts.count()).isZero();
        assertThat(fulfillments.findAll()).extracting(Fulfillment::getStatus)
                .containsExactlyInAnyOrder(FulfillmentStatus.FAILED, FulfillmentStatus.SUCCEEDED,
                        FulfillmentStatus.PROCESSING);
    }

    @Test
    void eachScanIsBoundedAndTheNextScanAdvances() {
        Order first = createOrder(OrderStatus.PAID);
        Order second = createOrder(OrderStatus.PAID);
        first.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        second.setCreatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        orders.saveAndFlush(first);
        orders.saveAndFlush(second);
        FulfillmentWorker singleOrderWorker = new FulfillmentWorker(fulfillments, service, 1);

        singleOrderWorker.processPendingFulfillments();

        assertCompleted(first);
        assertThat(orders.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(fulfillments.findByOrderId(second.getId())).isEmpty();

        singleOrderWorker.processPendingFulfillments();

        assertCompleted(second);
        assertThat(attempts.count()).isEqualTo(2);
    }

    @Test
    void startupFailureDoesNotPreventLaterOrdersAndCanRecoverNextScan() {
        Order first = createOrder(OrderStatus.PAID);
        Order second = createOrder(OrderStatus.PAID);
        doThrow(new IllegalStateException("Temporary startup failure"))
                .doCallRealMethod().when(service).fulfillOrder(first.getId());

        worker.processPendingFulfillments();

        assertThat(orders.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertCompleted(second);

        worker.processPendingFulfillments();

        assertCompleted(first);
        assertThat(attempts.count()).isEqualTo(2);
    }

    private void assertCompleted(Order order) {
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(fulfillments.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(FulfillmentStatus.SUCCEEDED);
    }

    private void createFulfillment(Order order, FulfillmentStatus status) {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.setOrderId(order.getId());
        fulfillment.setStatus(status);
        fulfillments.saveAndFlush(fulfillment);
    }

    private Order createOrder(OrderStatus status) {
        Order order = new Order();
        order.setOrderNumber("WORK-" + UUID.randomUUID());
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
