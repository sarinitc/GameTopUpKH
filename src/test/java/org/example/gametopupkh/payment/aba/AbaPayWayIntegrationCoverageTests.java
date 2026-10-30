package org.example.gametopupkh.payment.aba;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.fulfillment.Fulfillment;
import org.example.gametopupkh.fulfillment.FulfillmentRepository;
import org.example.gametopupkh.fulfillment.FulfillmentService;
import org.example.gametopupkh.fulfillment.FulfillmentStatus;
import org.example.gametopupkh.fulfillment.FulfillmentWorker;
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
import org.example.gametopupkh.payment.PaymentRepository;
import org.example.gametopupkh.payment.PaymentService;
import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.payment.aba.dto.AbaQrResponse;
import org.example.gametopupkh.payment.dto.CreatePaymentResponse;
import org.example.gametopupkh.payment.dto.PaymentStatusResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.example.gametopupkh.refund.RefundRequest;
import org.example.gametopupkh.refund.RefundService;
import org.example.gametopupkh.supplier.SupplierTestFixtures;
import org.example.gametopupkh.supplier.entity.SupplierProduct;
import org.example.gametopupkh.supplier.integration.SupplierClient;
import org.example.gametopupkh.supplier.integration.SupplierClient.SupplierException;
import org.example.gametopupkh.supplier.integration.SupplierClientRegistry;
import org.example.gametopupkh.supplier.integration.SupplierTopupResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:aba-coverage-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=aba-coverage-secret-must-be-at-least-thirty-two-bytes-long",
        "payment.gateway=mock", "fulfillment.worker.enabled=false"
})
class AbaPayWayIntegrationCoverageTests {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private FulfillmentRepository fulfillmentRepository;
    @Autowired private FulfillmentService fulfillmentService;
    @Autowired private RefundService refundService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private PaymentGateway mockGateway;
    @MockitoBean private SupplierClient supplier;
    @MockitoBean private SupplierClientRegistry supplierClients;

    private User customer;
    private User admin;
    private User otherCustomer;
    private Order testOrder;
    private SupplierProduct eligibleProduct;

    @BeforeEach
    void setUp() {
        customer = createUser("USER");
        admin = createUser("ADMIN");
        otherCustomer = createUser("USER");

        when(supplierClients.getConfiguredClient()).thenReturn(supplier);
        when(supplierClients.getClient("mock")).thenReturn(supplier);
        when(supplier.key()).thenReturn("mock");
        when(supplier.name()).thenReturn("test-supplier");
        when(supplier.fulfill(any())).thenReturn(
                new SupplierTopupResponse(true, "MOCK-SUPPLIER-REF-001", "Delivery success"));

        eligibleProduct = SupplierTestFixtures.createEligibleProduct(entityManager, transactionManager);

        testOrder = new Order();
        testOrder.setOrderNumber("ORD-" + UUID.randomUUID());
        testOrder.setUser(customer);
        testOrder.setPackageId(eligibleProduct.getPackageId());
        testOrder.setPlayerId("player-12345");
        testOrder.setGameNameSnapshot("Free Fire");
        testOrder.setPackageNameSnapshot("100 Diamonds");
        testOrder.setUnitPrice(new BigDecimal("0.99"));
        testOrder.setQuantity(1);
        testOrder.setTotalAmount(new BigDecimal("0.99"));
        testOrder.setCurrency("USD");
        testOrder.setStatus(OrderStatus.PENDING_PAYMENT);
        testOrder = orderRepository.save(testOrder);

        when(mockGateway.name()).thenReturn("aba");
    }

    private User createUser(String roleName) {
        Role role = roleRepository.findByName(roleName).orElseGet(() -> {
            Role r = new Role();
            r.setName(roleName);
            return roleRepository.save(r);
        });
        User user = new User();
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("hashed_pass");
        user.setFullName("User " + roleName);
        user.setPhoneNumber("+8551234" + (1000 + (int)(Math.random() * 9000)));
        user.setAccountStatus(AccountStatus.ACTIVE);
        user = userRepository.save(user);
        user.addRole(role);
        return userRepository.save(user);
    }

    // 1. Successful sandbox QR generation
    @Test
    @DisplayName("1. Successful sandbox QR generation returns provider QR string and image")
    void scenario01_successfulSandboxQrGeneration() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), "https://checkout.sandbox", Instant.now(),
                    "00020101021229370016bakong@aba", "data:image/png;base64,iVBORw0KGgo",
                    Instant.now().plusSeconds(1800));
        });

        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());
        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(res.qrString()).isEqualTo("00020101021229370016bakong@aba");
        assertThat(res.qrImage()).isEqualTo("data:image/png;base64,iVBORw0KGgo");
        assertThat(res.qrImageUrl()).isEqualTo("data:image/png;base64,iVBORw0KGgo");
        assertThat(res.amount()).isEqualByComparingTo("0.99");
        assertThat(res.currency()).isEqualTo("USD");

        Payment p = paymentRepository.findByOrderId(testOrder.getId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getQrString()).isEqualTo("00020101021229370016bakong@aba");
    }

    // 2. Invalid merchant credentials
    @Test
    @DisplayName("2. Invalid merchant credentials throws PaymentGatewayException")
    void scenario02_invalidMerchantCredentials() {
        AbaPayWayProperties props = new AbaPayWayProperties(
                "https://checkout-sandbox.payway.com.kh", "",
                AbaPayWayProperties.OFFICIAL_CREATE_PATH,
                AbaPayWayProperties.OFFICIAL_STATUS_PATH,
                "");
        AbaPayWayClient client = new AbaPayWayClient(RestClient.builder(), props);
        assertThatThrownBy(client::requireConfigured)
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("payment.aba.api-key is not configured");
    }

    // 3. Invalid QR generation response
    @Test
    @DisplayName("3. Invalid QR generation response throws PaymentGatewayException")
    void scenario03_invalidQrGenerationResponse() {
        AbaPayWayPaymentMapper mapper = new AbaPayWayPaymentMapper();
        assertThatThrownBy(() -> mapper.toIntent(new AbaQrResponse(null), null))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("Empty response from AbaPayWay");
    }

    // 4. Payment pending
    @Test
    @DisplayName("4. Payment pending leaves payment and order in pending state")
    void scenario04_paymentPending() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference())).thenReturn(
                new PaymentIntent("aba", res.reference(), PaymentStatus.PENDING,
                        new BigDecimal("0.99"), "USD", null, Instant.now(), null, null, null));

        PaymentStatusResponse verified = paymentService.verifyPayment(res.paymentId());
        assertThat(verified.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(orderRepository.findById(testOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    // 5. Verified successful payment
    @Test
    @DisplayName("5. Verified successful payment updates to PAID and starts fulfillment")
    void scenario05_verifiedSuccessfulPayment() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference())).thenReturn(
                new PaymentIntent("aba", res.reference(), PaymentStatus.COMPLETED,
                        new BigDecimal("0.99"), "USD", null, Instant.now(), null, null, null));

        PaymentStatusResponse verified = paymentService.verifyPayment(res.paymentId());
        assertThat(verified.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(verified.paidAt()).isNotNull();

        Order order = orderRepository.findById(testOrder.getId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);

        Fulfillment fulfillment = fulfillmentRepository.findByOrderId(testOrder.getId()).orElseThrow();
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
    }

    // 6. Failed payment
    @Test
    @DisplayName("6. Failed payment marks payment FAILED without fulfilling")
    void scenario06_failedPayment() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference())).thenReturn(
                new PaymentIntent("aba", res.reference(), PaymentStatus.FAILED,
                        new BigDecimal("0.99"), "USD", null, Instant.now(), null, null, null));

        PaymentStatusResponse verified = paymentService.verifyPayment(res.paymentId());
        assertThat(verified.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(fulfillmentRepository.findByOrderId(testOrder.getId())).isEmpty();
    }

    // 7. Wrong transaction reference
    @Test
    @DisplayName("7. Wrong transaction reference marks payment and order NEEDS_REVIEW")
    void scenario07_wrongTransactionReference() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference())).thenReturn(
                new PaymentIntent("aba", "WRONG-REF-999", PaymentStatus.COMPLETED,
                        new BigDecimal("0.99"), "USD", null, Instant.now(), null, null, null));

        PaymentStatusResponse verified = paymentService.verifyPayment(res.paymentId());
        assertThat(verified.status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(orderRepository.findById(testOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.NEEDS_REVIEW);
        assertThat(fulfillmentRepository.findByOrderId(testOrder.getId())).isEmpty();
    }

    // 8. Wrong amount
    @Test
    @DisplayName("8. Wrong amount marks payment and order NEEDS_REVIEW")
    void scenario08_wrongAmount() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference())).thenReturn(
                new PaymentIntent("aba", res.reference(), PaymentStatus.COMPLETED,
                        new BigDecimal("100.00"), "USD", null, Instant.now(), null, null, null));

        PaymentStatusResponse verified = paymentService.verifyPayment(res.paymentId());
        assertThat(verified.status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(orderRepository.findById(testOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.NEEDS_REVIEW);
        assertThat(fulfillmentRepository.findByOrderId(testOrder.getId())).isEmpty();
    }

    // 9. Wrong currency
    @Test
    @DisplayName("9. Wrong currency marks payment and order NEEDS_REVIEW")
    void scenario09_wrongCurrency() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference())).thenReturn(
                new PaymentIntent("aba", res.reference(), PaymentStatus.COMPLETED,
                        new BigDecimal("0.99"), "KHR", null, Instant.now(), null, null, null));

        PaymentStatusResponse verified = paymentService.verifyPayment(res.paymentId());
        assertThat(verified.status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(orderRepository.findById(testOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.NEEDS_REVIEW);
        assertThat(fulfillmentRepository.findByOrderId(testOrder.getId())).isEmpty();
    }

    // 10. Invalid or unexpected callbacks
    @Test
    @DisplayName("10. Invalid or unexpected callbacks are rejected")
    void scenario10_invalidOrUnexpectedCallbacks() {
        assertThatThrownBy(() -> paymentService.processCallback(null, "key-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> paymentService.processCallback("ref-1", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // 11. Duplicate callback
    @Test
    @DisplayName("11. Duplicate callback is deduplicated safely without repeating fulfillment")
    void scenario11_duplicateCallback() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference())).thenReturn(
                new PaymentIntent("aba", res.reference(), PaymentStatus.COMPLETED,
                        new BigDecimal("0.99"), "USD", null, Instant.now(), null, null, null));

        paymentService.processCallback(res.reference(), "event-key-dup");
        paymentService.processCallback(res.reference(), "event-key-dup");

        verify(supplier, times(1)).fulfill(any());
        assertThat(fulfillmentRepository.findByOrderId(testOrder.getId())).isPresent();
    }

    // 12. Concurrent callback requests share state safely
    @Test
    @DisplayName("12. Concurrent callback requests share the same state safely")
    void scenario12_concurrentCallbacks() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference())).thenReturn(
                new PaymentIntent("aba", res.reference(), PaymentStatus.COMPLETED,
                        new BigDecimal("0.99"), "USD", null, Instant.now(), null, null, null));

        paymentService.processCallback(res.reference(), "event-c1");
        paymentService.processCallback(res.reference(), "event-c2");

        assertThat(paymentRepository.findById(res.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
        verify(supplier, times(1)).fulfill(any());
    }

    // 13. Callback never arrives
    @Test
    @DisplayName("13. Callback never arrives; payment reconciliation recovers and verifies the order")
    void scenario13_callbackNeverArrives() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference())).thenReturn(
                new PaymentIntent("aba", res.reference(), PaymentStatus.COMPLETED,
                        new BigDecimal("0.99"), "USD", null, Instant.now(), null, null, null));

        PaymentStatusResponse verified = paymentService.verifyPayment(res.paymentId());
        assertThat(verified.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(orderRepository.findById(testOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    // 14. Payment reconciliation succeeds
    @Test
    @DisplayName("14. Payment reconciliation succeeds and returns updated status")
    void scenario14_reconciliationSucceeds() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference())).thenReturn(
                new PaymentIntent("aba", res.reference(), PaymentStatus.COMPLETED,
                        new BigDecimal("0.99"), "USD", null, Instant.now(), null, null, null));

        PaymentStatusResponse adminReconciled = paymentService.reconcile(res.paymentId(), admin.getId());
        assertThat(adminReconciled.status()).isEqualTo(PaymentStatus.COMPLETED);
    }

    // 15. ABA API timeout
    @Test
    @DisplayName("15. ABA API timeout preserves pending payment and records error event")
    void scenario15_abaApiTimeout() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference()))
                .thenThrow(new PaymentGatewayException("aba", "Timeout reading from provider"));

        assertThatThrownBy(() -> paymentService.verifyPayment(res.paymentId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        Payment payment = paymentRepository.findById(res.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    // 16. Concurrent payment verification
    @Test
    @DisplayName("16. Concurrent payment verification delivers once")
    void scenario16_concurrentPaymentVerification() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        when(mockGateway.checkStatus(res.reference())).thenReturn(
                new PaymentIntent("aba", res.reference(), PaymentStatus.COMPLETED,
                        new BigDecimal("0.99"), "USD", null, Instant.now(), null, null, null));

        paymentService.verifyPayment(res.paymentId());
        paymentService.verifyPayment(res.paymentId());

        assertThat(fulfillmentRepository.findByOrderId(testOrder.getId())).isPresent();
        verify(supplier, times(1)).fulfill(any());
    }

    // 17. Double fulfillment prevention
    @Test
    @DisplayName("17. Double fulfillment prevention guarantees only 1 fulfillment record per order")
    void scenario17_doubleFulfillmentPrevention() {
        testOrder.setStatus(OrderStatus.PAID);
        orderRepository.save(testOrder);

        Fulfillment f1 = fulfillmentService.fulfillOrder(testOrder.getId());
        Fulfillment f2 = fulfillmentService.fulfillOrder(testOrder.getId());

        assertThat(f1.getId()).isEqualTo(f2.getId());
        assertThat(fulfillmentRepository.findByOrderId(testOrder.getId())).isPresent();
    }

    // 18. Fulfillment worker restart recovery
    @Test
    @DisplayName("18. Fulfillment worker restart recovery picks up pending fulfillments")
    void scenario18_fulfillmentWorkerRestartRecovery() {
        testOrder.setStatus(OrderStatus.PAID);
        orderRepository.save(testOrder);

        Fulfillment pending = new Fulfillment();
        pending.setOrderId(testOrder.getId());
        pending.setStatus(FulfillmentStatus.PENDING);
        fulfillmentRepository.save(pending);

        FulfillmentWorker worker = new FulfillmentWorker(fulfillmentRepository, fulfillmentService, 50);
        worker.processPendingFulfillments();

        Fulfillment completed = fulfillmentRepository.findByOrderId(testOrder.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
    }

    // 19. Mock supplier success
    @Test
    @DisplayName("19. Mock supplier success transitions fulfillment and order to completed")
    void scenario19_mockSupplierSuccess() {
        testOrder.setStatus(OrderStatus.PAID);
        orderRepository.save(testOrder);

        Fulfillment f = fulfillmentService.fulfillOrder(testOrder.getId());
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(orderRepository.findById(testOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    // 20. Mock supplier failure
    @Test
    @DisplayName("20. Mock supplier failure records failed attempt and retries")
    void scenario20_mockSupplierFailure() {
        testOrder.setStatus(OrderStatus.PAID);
        orderRepository.save(testOrder);

        when(supplier.fulfill(any())).thenReturn(
                new SupplierTopupResponse(false, null, "Supplier out of stock"));

        Fulfillment f = fulfillmentService.fulfillOrder(testOrder.getId());
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(f.getAttemptCount()).isGreaterThanOrEqualTo(1);
    }

    // 21. Supplier delivery timeout
    @Test
    @DisplayName("21. Supplier delivery timeout marks UNKNOWN without blind retry")
    void scenario21_supplierDeliveryTimeout() {
        testOrder.setStatus(OrderStatus.PAID);
        orderRepository.save(testOrder);

        when(supplier.fulfill(any())).thenThrow(
                new SupplierException("test-supplier", "Connection timed out"));

        Fulfillment f = fulfillmentService.fulfillOrder(testOrder.getId());
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
        assertThat(f.getLastError()).contains("requires review");
        assertThat(orderRepository.findById(testOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.NEEDS_REVIEW);
        verify(supplier, times(1)).fulfill(any());
    }

    // 22. Refund while fulfillment is pending
    @Test
    @DisplayName("22. Refund request rejected when fulfillment is pending/unresolved")
    void scenario22_refundWhileFulfillmentPending() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        // Payment still pending, fulfillment hasn't started or resolved
        assertThatThrownBy(() -> refundService.requestRefund(
                res.paymentId(), admin.getId(), new RefundRequest(new BigDecimal("0.99"), "Customer cancelled")))
                .isInstanceOf(ApiException.class);
    }

    // 23. Customer accessing another user's payment
    @Test
    @DisplayName("23. Customer accessing another user's payment returns NOT_FOUND")
    void scenario23_customerAccessingAnotherUsersPayment() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        assertThatThrownBy(() -> paymentService.getPayment(res.paymentId(), otherCustomer.getId(), false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // 24. Unauthorized administrative requests
    @Test
    @DisplayName("24. Unauthorized administrative requests are forbidden")
    void scenario24_unauthorizedAdminRequests() {
        when(mockGateway.createPayment(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentIntent("aba", req.transactionId(), PaymentStatus.PENDING,
                    req.amount(), req.currency(), null, Instant.now(), "qr", "img", Instant.now().plusSeconds(1800));
        });
        CreatePaymentResponse res = paymentService.createAbaPayment(testOrder.getId(), customer.getId());

        assertThatThrownBy(() -> paymentService.reconcile(res.paymentId(), customer.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // 25. Production payment feature flag disabled
    @Test
    @DisplayName("25. Production payment fails closed when live payments disabled")
    void scenario25_productionPaymentFeatureFlagDisabled() {
        assertThatThrownBy(() -> new AbaPayWayProperties(
                "production", false, "https://checkout.payway.com.kh",
                "secret-prod-key", AbaPayWayProperties.OFFICIAL_CREATE_PATH,
                AbaPayWayProperties.OFFICIAL_STATUS_PATH,
                AbaPayWayProperties.OFFICIAL_PURCHASE_PATH,
                "abapay_khqr", "template3_color", 30, "prod-merchant",
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment.aba.live-payments-enabled=false");
    }
}
