package org.example.gametopupkh.fulfillment;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.fulfillment.supplier.SupplierClient;
import org.example.gametopupkh.fulfillment.supplier.SupplierClient.SupplierResult;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.order.OrderStatusHistory;
import org.example.gametopupkh.order.OrderStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
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
        "supplier.max-attempts=2"
})
@Transactional
class FulfillmentServiceTests {

    @Autowired private FulfillmentService service;
    @Autowired private FulfillmentRepository fulfillments;
    @Autowired private OrderRepository orders;
    @Autowired private OrderStatusHistoryRepository history;
    @MockitoBean private SupplierClient supplier;

    private Order order;

    @BeforeEach
    void setUp() {
        when(supplier.name()).thenReturn("test-supplier");
        order = new Order();
        order.setOrderNumber("FUL-" + UUID.randomUUID());
        order.setPackageId(UUID.randomUUID());
        order.setPlayerId("player-1");
        order.setGameNameSnapshot("Game");
        order.setPackageNameSnapshot("Package");
        order.setUnitPrice(new BigDecimal("4.25"));
        order.setTotalAmount(new BigDecimal("4.25"));
        order.setCurrency("USD");
        order.setStatus(OrderStatus.PAID);
        order = orders.save(order);
    }

    @Test
    void paidOrderIsDeliveredAndCompleted() {
        when(supplier.fulfill(any()))
                .thenReturn(SupplierResult.success("SUP-1", "Delivered"));

        Fulfillment fulfillment = service.fulfillOrder(order.getId());

        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(fulfillment.getAttemptCount()).isEqualTo(1);
        assertThat(fulfillment.getSupplierReference()).isEqualTo("SUP-1");
        assertThat(fulfillment.getCompletedAt()).isNotNull();
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
                .thenReturn(SupplierResult.success("SUP-1", "Delivered"));

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
                .thenReturn(SupplierResult.failure("Supplier is temporarily unavailable"))
                .thenReturn(SupplierResult.failure("Supplier is temporarily unavailable"))
                .thenReturn(SupplierResult.success("SUP-2", "Delivered"));

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
}
