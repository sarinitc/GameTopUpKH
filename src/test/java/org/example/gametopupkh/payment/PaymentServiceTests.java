package org.example.gametopupkh.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.order.OrderStatusHistoryRepository;
import org.example.gametopupkh.payment.dto.CreatePaymentResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:payment-service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=payment-service-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "fulfillment.worker.enabled=false"
})
class PaymentServiceTests {

    @Autowired private PaymentService service;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentEventRepository events;
    @Autowired private OrderRepository orders;
    @Autowired private OrderStatusHistoryRepository history;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @MockitoBean private PaymentGateway gateway;

    private User customer;
    private User admin;
    private Order order;

    @BeforeEach
    void setUp() {
        customer = createUser("USER");
        admin = createUser("ADMIN");
        order = new Order();
        order.setOrderNumber("TEST-" + UUID.randomUUID());
        order.setUser(customer);
        order.setPackageId(UUID.randomUUID());
        order.setPlayerId("player");
        order.setGameNameSnapshot("Game");
        order.setPackageNameSnapshot("Package");
        order.setUnitPrice(new BigDecimal("4.25"));
        order.setTotalAmount(new BigDecimal("4.25"));
        order.setCurrency("USD");
        order = orders.save(order);
        when(gateway.name()).thenReturn("aba");
        when(gateway.createPayment(any())).thenAnswer(invocation -> {
            PaymentRequest request = invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Payment reserved = payments.findByOrderId(order.getId()).orElseThrow();
            assertThat(reserved.getStatus()).isEqualTo(PaymentStatus.INITIALIZING);
            assertThat(reserved.getReference()).isEqualTo(request.transactionId()).matches("[A-Za-z0-9]{1,20}");
            assertThat(request.amount()).isEqualByComparingTo("4.25");
            assertThat(request.currency()).isEqualTo("USD");
            return intent(request.transactionId(), PaymentStatus.PENDING, new BigDecimal("4.25"));
        });
    }

    @Test
    void creationCommitsStableReferenceBeforeNetworkAndReusesTheExistingQr() {
        CreatePaymentResponse first = create();
        CreatePaymentResponse second = create();
        assertThat(second).isEqualTo(first);
        assertThat(first.qrString()).isEqualTo("test-qr");
        assertThat(first.provider()).isEqualTo(PaymentProvider.ABA);
        assertThat(payments.findByOrderId(order.getId())).isPresent();
        verify(gateway, times(1)).createPayment(any());
    }

    @Test
    void creationRejectsAnUnrelatedOwnerAndInactiveAccount() {
        assertApiError(() -> service.createAbaPayment(order.getId(), admin.getId()), HttpStatus.NOT_FOUND);
        customer.setAccountStatus(AccountStatus.SUSPENDED);
        users.save(customer);
        assertApiError(this::create, HttpStatus.FORBIDDEN);
        assertThat(payments.findByOrderId(order.getId())).isEmpty();
        verify(gateway, never()).createPayment(any());
    }

    @Test
    void creationRejectsAnOrderThatIsNoLongerAwaitingPayment() {
        order.setStatus(OrderStatus.CANCELLED);
        orders.save(order);
        assertApiError(this::create, HttpStatus.CONFLICT);
        verify(gateway, never()).createPayment(any());
    }

    @Test
    void readsArePersistedOnlyAndHideOtherCustomersPayment() {
        CreatePaymentResponse payment = create();
        assertThat(service.getPayment(payment.paymentId(), customer.getId(), false).status())
                .isEqualTo(PaymentStatus.PENDING);
        assertApiError(() -> service.getPayment(payment.paymentId(), UUID.randomUUID(), false), HttpStatus.NOT_FOUND);
        assertThat(service.getPayment(payment.paymentId(), admin.getId(), true).paymentId()).isEqualTo(payment.paymentId());
        verify(gateway, never()).checkStatus(anyString());
    }

    @Test
    void verifiedCompletionMarksOrderPaidOnceAndDeduplicatesCallbacks() {
        CreatePaymentResponse payment = create();
        when(gateway.checkStatus(payment.reference())).thenReturn(intent(payment.reference(), PaymentStatus.COMPLETED, new BigDecimal("4.25")));
        service.processCallback(payment.reference(), "callback-one");
        service.processCallback(payment.reference(), "callback-one");
        service.reconcile(payment.paymentId(), admin.getId());
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(history.findAllByOrderIdOrderByChangedAtAsc(order.getId())).hasSize(3);
        assertThat(events.findAllByPaymentIdOrderByCreatedAtAsc(payment.paymentId()))
                .filteredOn(event -> "callback-one".equals(event.getEventKey())).hasSize(1);
        assertThat(service.getPayment(payment.paymentId(), customer.getId(), false).paidAt()).isNotNull();
    }

    @Test
    void mismatchedAmountNeverPaysTheOrderAndRequiresReview() {
        CreatePaymentResponse payment = create();
        when(gateway.checkStatus(payment.reference())).thenReturn(intent(payment.reference(), PaymentStatus.COMPLETED, new BigDecimal("0.01")));
        assertThat(service.reconcile(payment.paymentId(), admin.getId()).status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(history.findAllByOrderIdOrderByChangedAtAsc(order.getId())).isEmpty();
    }

    @Test
    void callbackTransportErrorCanRetryTheSameEventAndKeepsSanitizedAudit() {
        CreatePaymentResponse payment = create();
        when(gateway.checkStatus(payment.reference()))
                .thenThrow(new PaymentGatewayException("aba", "secret provider credential"))
                .thenReturn(intent(payment.reference(), PaymentStatus.COMPLETED, new BigDecimal("4.25")));
        assertApiError(() -> service.processCallback(payment.reference(), "retry-event"), HttpStatus.BAD_GATEWAY);
        assertThat(events.findAllByPaymentIdOrderByCreatedAtAsc(payment.paymentId()))
                .noneMatch(event -> "retry-event".equals(event.getEventKey()))
                .noneMatch(event -> event.getNote() != null && event.getNote().contains("secret"));
        service.processCallback(payment.reference(), "retry-event");
        assertThat(payments.findById(payment.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void uncertainCreationIsDurablyReservedAndCannotIssueAnotherCharge() {
        doThrow(new PaymentGatewayException("aba", "secret provider credential"))
                .when(gateway).createPayment(any());
        assertApiError(this::create, HttpStatus.BAD_GATEWAY);
        Payment payment = payments.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertApiError(this::create, HttpStatus.CONFLICT);
        assertThat(events.findAllByPaymentIdOrderByCreatedAtAsc(payment.getId()))
                .anyMatch(event -> "CREATE_ERROR".equals(event.getType()))
                .noneMatch(event -> event.getNote() != null && event.getNote().contains("secret"));
        verify(gateway, times(1)).createPayment(any());
    }

    @Test
    void serviceReconciliationRequiresAnActiveAdmin() {
        CreatePaymentResponse payment = create();
        assertApiError(() -> service.reconcile(payment.paymentId(), customer.getId()), HttpStatus.FORBIDDEN);
        verify(gateway, never()).checkStatus(anyString());
    }

    @Test
    void mockConfirmationDoesNotDependOnEphemeralGatewayState() {
        when(gateway.name()).thenReturn("mock");
        CreatePaymentResponse payment = create();
        when(gateway.checkStatus(anyString()))
                .thenThrow(new PaymentGatewayException("mock", "Unknown payment reference"));
        assertThat(service.confirmMockPayment(payment.paymentId().toString()).status())
                .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(gateway, never()).checkStatus(anyString());
    }

    @Test
    void mockReconciliationDoesNotDependOnEphemeralGatewayState() {
        when(gateway.name()).thenReturn("mock");
        CreatePaymentResponse payment = create();
        when(gateway.checkStatus(anyString()))
                .thenThrow(new PaymentGatewayException("mock", "Unknown payment reference"));
        assertThat(service.reconcile(payment.paymentId(), admin.getId()).status())
                .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(gateway, never()).checkStatus(anyString());
    }

    @Test
    void latePendingCheckCannotDowngradeAConcurrentCompletion() {
        CreatePaymentResponse payment = create();
        AtomicInteger checks = new AtomicInteger();
        when(gateway.checkStatus(payment.reference())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (checks.incrementAndGet() == 1) {
                service.reconcile(payment.paymentId(), admin.getId());
                return intent(payment.reference(), PaymentStatus.PENDING, new BigDecimal("4.25"));
            }
            return intent(payment.reference(), PaymentStatus.COMPLETED, new BigDecimal("4.25"));
        });
        assertThat(service.reconcile(payment.paymentId(), admin.getId()).status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(history.findAllByOrderIdOrderByChangedAtAsc(order.getId())).hasSize(3);
    }

    @Test
    void verifiedPaymentForCancelledOrderRequiresReviewWithoutReopeningTheOrder() {
        CreatePaymentResponse payment = create();
        Order stored = orders.findById(order.getId()).orElseThrow();
        stored.setStatus(OrderStatus.CANCELLED);
        orders.save(stored);
        when(gateway.checkStatus(payment.reference())).thenReturn(intent(payment.reference(), PaymentStatus.COMPLETED, new BigDecimal("4.25")));
        assertThat(service.reconcile(payment.paymentId(), admin.getId()).status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(history.findAllByOrderIdOrderByChangedAtAsc(order.getId())).isEmpty();
    }

    @Test
    void configuredGatewayChangeCannotVerifyAnExistingPayment() {
        CreatePaymentResponse payment = create();
        when(gateway.name()).thenReturn("mock");
        assertApiError(() -> service.reconcile(payment.paymentId(), admin.getId()), HttpStatus.CONFLICT);
        assertApiError(() -> service.processCallback(payment.reference(), "wrong-gateway"), HttpStatus.CONFLICT);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        verify(gateway, never()).checkStatus(anyString());
    }

    private CreatePaymentResponse create() {
        return service.createAbaPayment(order.getId(), customer.getId());
    }

    private PaymentIntent intent(String reference, PaymentStatus status, BigDecimal amount) {
        return new PaymentIntent("aba", reference, status, amount, "USD", "https://pay.example/qr",
                Instant.now(), "test-qr", "https://pay.example/qr.png", Instant.now().plusSeconds(300));
    }

    private User createUser(String roleName) {
        Role role = roles.findByName(roleName).orElseGet(() -> {
            Role value = new Role();
            value.setName(roleName);
            return roles.save(value);
        });
        User user = new User();
        user.setFullName("Payment Tester");
        user.setEmail("payment-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("unused-password-hash");
        user = users.save(user);
        user.addRole(role);
        return users.save(user);
    }

    private void assertApiError(Runnable action, HttpStatus expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getStatus()).isEqualTo(expected));
    }
}
