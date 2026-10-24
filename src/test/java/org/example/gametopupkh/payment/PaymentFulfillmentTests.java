package org.example.gametopupkh.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.example.gametopupkh.fulfillment.Fulfillment;
import org.example.gametopupkh.fulfillment.FulfillmentAttempt;
import org.example.gametopupkh.fulfillment.FulfillmentRepository;
import org.example.gametopupkh.fulfillment.FulfillmentService;
import org.example.gametopupkh.fulfillment.FulfillmentStatus;
import org.example.gametopupkh.supplier.SupplierTestFixtures;
import org.example.gametopupkh.supplier.integration.SupplierClient;
import org.example.gametopupkh.supplier.integration.SupplierClientRegistry;
import org.example.gametopupkh.supplier.integration.SupplierTopupResponse;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.payment.dto.CreatePaymentResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:payment-fulfillment;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=payment-fulfillment-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.max-attempts=2", "fulfillment.worker.enabled=false"
})
class PaymentFulfillmentTests {

    @Autowired private PaymentService service;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderRepository orders;
    @Autowired private FulfillmentRepository fulfillments;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @MockitoBean private PaymentGateway gateway;
    @MockitoBean private SupplierClient supplier;
    @MockitoBean private SupplierClientRegistry supplierClients;
    @MockitoSpyBean private FulfillmentService fulfillmentService;

    private User customer;
    private User admin;
    private Order order;

    @BeforeEach
    void setUp() {
        when(supplierClients.getConfiguredClient()).thenReturn(supplier);
        when(supplierClients.getClient("mock")).thenReturn(supplier);
        when(supplier.key()).thenReturn("mock");
        customer = createUser("USER");
        admin = createUser("ADMIN");
        order = new Order();
        order.setOrderNumber("FULFILL-" + UUID.randomUUID());
        order.setUser(customer);
        order.setPackageId(SupplierTestFixtures.createEligibleProduct(entityManager, transactionManager).getPackageId());
        order.setPlayerId("player-42");
        order.setGameNameSnapshot("Game");
        order.setPackageNameSnapshot("Package");
        order.setUnitPrice(new BigDecimal("4.25"));
        order.setTotalAmount(new BigDecimal("4.25"));
        order.setCurrency("USD");
        order = orders.save(order);
        when(gateway.name()).thenReturn("aba");
        when(gateway.createPayment(any())).thenAnswer(invocation -> {
            PaymentRequest request = invocation.getArgument(0);
            return intent(request.transactionId(), PaymentStatus.PENDING, "4.25");
        });
        when(gateway.checkStatus(any())).thenAnswer(invocation ->
                intent(invocation.getArgument(0), PaymentStatus.COMPLETED, "4.25"));
        when(supplier.name()).thenReturn("test-supplier");
        when(supplier.fulfill(any())).thenReturn(SupplierTopupResponse.success("SUP-42", "Delivered"));
    }

    enum RecoveryPath {
        DUPLICATE_CALLBACK, NEW_CALLBACK, RECONCILE, CREATE_PAYMENT, MOCK_CONFIRMATION
    }

    @Test
    void duplicateAndNewCompletionCallbacksDoNotRepeatSuccessfulDelivery() {
        CreatePaymentResponse payment = createPayment();

        service.processCallback(payment.reference(), "completion");
        UUID fulfillmentId = fulfillments.findByOrderId(order.getId()).orElseThrow().getId();
        service.processCallback(payment.reference(), "completion");
        service.processCallback(payment.reference(), "another-completion-event");

        assertDelivered(payment);
        assertThat(fulfillments.findByOrderId(order.getId()).orElseThrow().getId()).isEqualTo(fulfillmentId);
        assertThat(fulfillmentService.findAttempts(order.getId())).hasSize(1);
        verify(supplier, times(1)).fulfill(any());
    }

    @Test
    void duplicateAndNewCompletionCallbacksDoNotRepeatUnknownDelivery() {
        CreatePaymentResponse payment = createPayment();
        when(supplier.fulfill(any())).thenThrow(
                new SupplierClient.SupplierException("test-supplier", "Connection closed after submission"));

        service.processCallback(payment.reference(), "completion");
        UUID fulfillmentId = fulfillments.findByOrderId(order.getId()).orElseThrow().getId();
        service.processCallback(payment.reference(), "completion");
        service.processCallback(payment.reference(), "another-completion-event");

        assertThat(storedPayment(payment).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);
        Fulfillment fulfillment = fulfillments.findByOrderId(order.getId()).orElseThrow();
        assertThat(fulfillment.getId()).isEqualTo(fulfillmentId);
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
        assertThat(fulfillment.getAttemptCount()).isEqualTo(1);
        assertThat(fulfillmentService.findAttempts(order.getId()))
                .extracting(FulfillmentAttempt::getStatus)
                .containsExactly(FulfillmentAttempt.Status.PENDING);
        verify(supplier, times(1)).fulfill(any());
    }

    @ParameterizedTest
    @EnumSource(RecoveryPath.class)
    void successfulPaymentCanRecoverAStartupFailure(RecoveryPath recoveryPath) {
        if (recoveryPath == RecoveryPath.MOCK_CONFIRMATION) {
            when(gateway.name()).thenReturn("mock");
        }
        CreatePaymentResponse payment = createPayment();
        doThrow(new IllegalStateException("Fulfillment temporarily unavailable"))
                .doCallRealMethod().when(fulfillmentService).fulfillOrder(order.getId());

        service.processCallback(payment.reference(), "completion");

        assertThat(storedPayment(payment).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(fulfillments.findByOrderId(order.getId())).isEmpty();

        switch (recoveryPath) {
            case DUPLICATE_CALLBACK -> service.processCallback(payment.reference(), "completion");
            case NEW_CALLBACK -> service.processCallback(payment.reference(), "completion-again");
            case RECONCILE -> service.reconcile(payment.paymentId(), admin.getId());
            case CREATE_PAYMENT -> createPayment();
            case MOCK_CONFIRMATION -> service.confirmMockPayment(payment.paymentId().toString());
        }

        assertDelivered(payment);
        verify(supplier, times(1)).fulfill(any());
    }

    @Test
    void supplierFailureLeavesTheVerifiedPaymentCompleted() {
        CreatePaymentResponse payment = createPayment();
        when(supplier.fulfill(any())).thenReturn(SupplierTopupResponse.failure("Delivery rejected"));

        service.processCallback(payment.reference(), "completion");
        service.processCallback(payment.reference(), "completion");

        assertThat(storedPayment(payment).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(storedPayment(payment).getPaidAt()).isNotNull();
        assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);
        Fulfillment fulfillment = fulfillments.findByOrderId(order.getId()).orElseThrow();
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(fulfillment.getAttemptCount()).isEqualTo(2);
        verify(supplier, times(2)).fulfill(any());
    }

    @Test
    void mismatchedProviderAmountDoesNotStartFulfillment() {
        CreatePaymentResponse payment = createPayment();
        when(gateway.checkStatus(payment.reference())).thenReturn(
                intent(payment.reference(), PaymentStatus.COMPLETED, "0.01"));

        service.processCallback(payment.reference(), "mismatch");

        assertThat(storedPayment(payment).getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(fulfillments.findByOrderId(order.getId())).isEmpty();
        verify(supplier, never()).fulfill(any());
    }

    @Test
    void rollingBackPaymentCompletionDoesNotDeliverTheOrder() {
        CreatePaymentResponse payment = createPayment();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.processCallback(payment.reference(), "rolled-back-completion");
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(fulfillments.findByOrderId(order.getId())).isEmpty();
            status.setRollbackOnly();
        });

        assertThat(storedPayment(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(fulfillments.findByOrderId(order.getId())).isEmpty();
        verify(supplier, never()).fulfill(any());
    }

    @Test
    void committingPaymentCompletionDeliversInANewTransaction() {
        CreatePaymentResponse payment = createPayment();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.processCallback(payment.reference(), "committed-completion");
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(fulfillments.findByOrderId(order.getId())).isEmpty();
            verify(supplier, never()).fulfill(any());
        });

        assertDelivered(payment);
    }

    private void assertDelivered(CreatePaymentResponse payment) {
        assertThat(storedPayment(payment).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        Fulfillment fulfillment = fulfillments.findByOrderId(order.getId()).orElseThrow();
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(fulfillment.getAttemptCount()).isEqualTo(1);
        assertThat(fulfillment.getSupplierReference()).isEqualTo("SUP-42");
    }

    private Order storedOrder() {
        return orders.findById(order.getId()).orElseThrow();
    }

    private Payment storedPayment(CreatePaymentResponse payment) {
        return payments.findById(payment.paymentId()).orElseThrow();
    }

    private CreatePaymentResponse createPayment() {
        return service.createAbaPayment(order.getId(), customer.getId());
    }

    private PaymentIntent intent(String reference, PaymentStatus status, String amount) {
        return new PaymentIntent("aba", reference, status, new BigDecimal(amount), "USD",
                null, Instant.now(), null, null, Instant.now().plusSeconds(300));
    }

    private User createUser(String roleName) {
        Role role = roles.findByName(roleName).orElseGet(() -> {
            Role value = new Role();
            value.setName(roleName);
            return roles.save(value);
        });
        User user = new User();
        user.setFullName("Fulfillment Payment Tester");
        user.setEmail("fulfillment-payment-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("unused-password-hash");
        user = users.save(user);
        user.addRole(role);
        return users.save(user);
    }
}
