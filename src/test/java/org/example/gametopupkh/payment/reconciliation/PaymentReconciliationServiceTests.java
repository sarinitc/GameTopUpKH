package org.example.gametopupkh.payment.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.fulfillment.Fulfillment;
import org.example.gametopupkh.fulfillment.FulfillmentAttemptRepository;
import org.example.gametopupkh.fulfillment.FulfillmentRepository;
import org.example.gametopupkh.fulfillment.FulfillmentStatus;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.order.OrderStatusHistoryRepository;
import org.example.gametopupkh.payment.Payment;
import org.example.gametopupkh.payment.PaymentEventRepository;
import org.example.gametopupkh.payment.PaymentProvider;
import org.example.gametopupkh.payment.PaymentRepository;
import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.payment.gateway.PaymentGateway;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:payment-reconciliation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=payment-reconciliation-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "payment.reconcile.cooldown=PT0S",
        "payment.reconciliation.min-age=-PT1S", "payment.reconciliation.enabled=false",
        "fulfillment.worker.enabled=false",
        "supplier.client=mock", "supplier.mock.fail=false"
})
class PaymentReconciliationServiceTests {

    @Autowired private PaymentReconciliationService service;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentEventRepository events;
    @Autowired private OrderRepository orders;
    @Autowired private OrderStatusHistoryRepository orderHistory;
    @Autowired private FulfillmentRepository fulfillments;
    @Autowired private FulfillmentAttemptRepository attempts;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @MockitoBean private PaymentGateway gateway;

    private User customer;

    @BeforeEach
    void setUp() {
        attempts.deleteAll();
        fulfillments.deleteAll();
        events.deleteAll();
        payments.deleteAll();
        orderHistory.deleteAll();
        orders.deleteAll();
        customer = createUser("USER");
        when(gateway.name()).thenReturn("aba");
    }

    @Test
    void completedProviderStatusUpdatesPaymentOrderAndStartsFulfillmentOnce() {
        Order order = createOrder();
        Payment payment = createPayment(order, PaymentStatus.PENDING, order.getTotalAmount(), order.getCurrency());
        when(gateway.checkStatus(payment.getReference())).thenReturn(
                intent(payment.getReference(), PaymentStatus.COMPLETED, order.getTotalAmount(), order.getCurrency()));

        PaymentReconciliationResult result = service.reconcile();

        assertThat(result.inspected()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(result.errors()).isZero();
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
        assertThat(fulfillments.findByOrderId(order.getId()))
                .get()
                .extracting(Fulfillment::getStatus)
                .isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(events.findAllByPaymentIdOrderByCreatedAtAsc(payment.getId()))
                .anyMatch(event -> "RECONCILIATION".equals(event.getType()));

        assertThat(service.reconcile().inspected()).isZero();
    }

    @Test
    void pendingProviderStatusLeavesThePaymentUnresolved() {
        Order order = createOrder();
        Payment payment = createPayment(order, PaymentStatus.PENDING, order.getTotalAmount(), order.getCurrency());
        when(gateway.checkStatus(payment.getReference())).thenReturn(
                intent(payment.getReference(), PaymentStatus.PENDING, order.getTotalAmount(), order.getCurrency()));

        PaymentReconciliationResult result = service.reconcile();

        assertThat(result.pending()).isEqualTo(1);
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(fulfillments.findByOrderId(order.getId())).isEmpty();
    }

    @Test
    void stalledInitializingPaymentIsResolvedByTheProviderStatus() {
        Order order = createOrder();
        Payment payment = createPayment(order, PaymentStatus.INITIALIZING,
                order.getTotalAmount(), order.getCurrency());
        when(gateway.checkStatus(payment.getReference())).thenReturn(
                intent(payment.getReference(), PaymentStatus.COMPLETED, order.getTotalAmount(), order.getCurrency()));

        PaymentReconciliationResult result = service.reconcile();

        assertThat(result.completed()).isEqualTo(1);
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void amountMismatchMovesThePaymentToNeedsReviewWithoutFulfillment() {
        Order order = createOrder();
        Payment payment = createPayment(order, PaymentStatus.PENDING, order.getTotalAmount(), order.getCurrency());
        when(gateway.checkStatus(payment.getReference())).thenReturn(
                intent(payment.getReference(), PaymentStatus.COMPLETED, new BigDecimal("1.00"), order.getCurrency()));

        PaymentReconciliationResult result = service.reconcile();

        assertThat(result.needsReview()).isEqualTo(1);
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(fulfillments.findByOrderId(order.getId())).isEmpty();
    }

    @Test
    void providerFailureIsAuditedAndDoesNotStopTheRestOfTheBatch() {
        Order failingOrder = createOrder();
        Payment failingPayment = createPayment(failingOrder, PaymentStatus.PENDING,
                failingOrder.getTotalAmount(), failingOrder.getCurrency());
        Order pendingOrder = createOrder();
        Payment pendingPayment = createPayment(pendingOrder, PaymentStatus.PENDING,
                pendingOrder.getTotalAmount(), pendingOrder.getCurrency());
        when(gateway.checkStatus(failingPayment.getReference()))
                .thenThrow(new PaymentGatewayException("aba", "provider unavailable"));
        when(gateway.checkStatus(pendingPayment.getReference())).thenReturn(
                intent(pendingPayment.getReference(), PaymentStatus.PENDING,
                        pendingOrder.getTotalAmount(), pendingOrder.getCurrency()));

        PaymentReconciliationResult result = service.reconcile();

        assertThat(result.inspected()).isEqualTo(2);
        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.pending()).isEqualTo(1);
        assertThat(events.findAllByPaymentIdOrderByCreatedAtAsc(failingPayment.getId()))
                .anyMatch(event -> "RECONCILIATION_ERROR".equals(event.getType()));
        assertThat(payments.findById(pendingPayment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void needsReviewPaymentCanBeResolvedByAConfirmedProviderStatus() {
        Order order = createOrder();
        Payment payment = createPayment(order, PaymentStatus.NEEDS_REVIEW,
                order.getTotalAmount(), order.getCurrency());
        when(gateway.checkStatus(payment.getReference())).thenReturn(
                intent(payment.getReference(), PaymentStatus.COMPLETED, order.getTotalAmount(), order.getCurrency()));

        PaymentReconciliationResult result = service.reconcile();

        assertThat(result.completed()).isEqualTo(1);
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void finalPaymentsAreNotInspected() {
        Order order = createOrder();
        createPayment(order, PaymentStatus.COMPLETED, order.getTotalAmount(), order.getCurrency());

        PaymentReconciliationResult result = service.reconcile();

        assertThat(result.inspected()).isZero();
        verify(gateway, never()).checkStatus(anyString());
    }

    @Test
    void nonAbaGatewaysAreNotReconciled() {
        when(gateway.name()).thenReturn("mock");

        PaymentReconciliationResult result = service.reconcile();

        assertThat(result.inspected()).isZero();
        verify(gateway, never()).checkStatus(anyString());
    }

    private Order createOrder() {
        Order order = new Order();
        order.setOrderNumber("REC-" + UUID.randomUUID());
        order.setUser(customer);
        order.setPackageId(UUID.randomUUID());
        order.setPlayerId("player-1");
        order.setGameNameSnapshot("Game");
        order.setPackageNameSnapshot("Package");
        order.setUnitPrice(new BigDecimal("9.99"));
        order.setTotalAmount(new BigDecimal("9.99"));
        order.setCurrency("USD");
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        return orders.save(order);
    }

    private Payment createPayment(Order order, PaymentStatus status, BigDecimal amount, String currency) {
        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setProvider(PaymentProvider.ABA);
        payment.setReference("PAY" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        payment.setStatus(status);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        return payments.save(payment);
    }

    private PaymentIntent intent(String reference, PaymentStatus status, BigDecimal amount, String currency) {
        return new PaymentIntent("aba", reference, status, amount, currency, null, Instant.now());
    }

    private User createUser(String roleName) {
        Role role = roles.findByName(roleName).orElseGet(() -> {
            Role value = new Role();
            value.setName(roleName);
            return roles.save(value);
        });
        User user = new User();
        user.setFullName("Reconciliation Tester");
        user.setEmail("reconciliation-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("unused-password-hash");
        user.setAccountStatus(AccountStatus.ACTIVE);
        user = users.save(user);
        user.addRole(role);
        return users.save(user);
    }
}
