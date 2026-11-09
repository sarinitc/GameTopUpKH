package org.example.gametopupkh.payment.bakong;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.fulfillment.FulfillmentService;
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
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse.BakongTransactionData;
import org.example.gametopupkh.payment.dto.PaymentStatusResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.supplier.SupplierTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:bakong-verification;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=payment-service-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "fulfillment.worker.enabled=false",
        "payment.bakong.account-id=merchant@bank"
})
class BakongVerificationServiceTests {

    @Autowired private BakongVerificationService verificationService;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentEventRepository events;
    @Autowired private OrderRepository orders;
    @Autowired private OrderStatusHistoryRepository history;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private BakongApiClient apiClient;
    @MockitoBean private FulfillmentService fulfillmentService;

    private User customer;
    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        customer = createUser("USER");
        order = new Order();
        order.setOrderNumber("ORD-" + UUID.randomUUID());
        order.setUser(customer);
        order.setPackageId(SupplierTestFixtures.createEligibleProduct(entityManager, transactionManager).getPackageId());
        order.setPlayerId("player123");
        order.setGameNameSnapshot("Free Fire");
        order.setPackageNameSnapshot("100 Diamonds");
        order.setUnitPrice(new BigDecimal("4.25"));
        order.setTotalAmount(new BigDecimal("4.25"));
        order.setCurrency("USD");
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order = orders.save(order);

        payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setProvider(PaymentProvider.BAKONG);
        payment.setReference("BAK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(new BigDecimal("4.25"));
        payment.setCurrency("USD");
        payment.setQrString("sample-khqr-string");
        payment.setQrMd5("md5-" + UUID.randomUUID().toString().replace("-", "").substring(0, 28));
        payment.setExpiresAt(Instant.now().plusSeconds(600));
        payment = payments.save(payment);
    }

    @Test
    @DisplayName("Successful verification marks payment COMPLETED, order PAID, and triggers fulfillment")
    void successfulVerificationMarksPaidAndFulfills() {
        String txHash = "hash-" + UUID.randomUUID();
        BakongTransactionData data = new BakongTransactionData(
                txHash,
                "customer@devb",
                "merchant@bank",
                "USD",
                new BigDecimal("4.25"),
                "Order description",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "EXT-001"
        );
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        PaymentStatusResponse response = verificationService.verifyPayment(payment.getId(), customer.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(response.gatewayReference()).isEqualTo(txHash);

        Payment updatedPayment = payments.findById(payment.getId()).orElseThrow();
        assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(updatedPayment.getPaidAt()).isNotNull();
        assertThat(updatedPayment.getGatewayReference()).isEqualTo(txHash);
        assertThat(updatedPayment.getBakongHash()).isEqualTo(txHash);

        Order updatedOrder = orders.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.PAID);

        verify(fulfillmentService, times(1)).fulfillOrder(order.getId());
    }

    @Test
    @DisplayName("Idempotent verification returns existing state and does not trigger duplicate fulfillment")
    void repeatedVerificationIsIdempotent() {
        String txHash = "hash-" + UUID.randomUUID();
        BakongTransactionData data = new BakongTransactionData(
                txHash,
                "customer@devb",
                "merchant@bank",
                "USD",
                new BigDecimal("4.25"),
                "Order description",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "EXT-001"
        );
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        // First verification
        verificationService.verifyPayment(payment.getId(), customer.getId());

        // Second verification
        PaymentStatusResponse secondResponse = verificationService.verifyPayment(payment.getId(), customer.getId());

        assertThat(secondResponse.status()).isEqualTo(PaymentStatus.COMPLETED);
        verify(apiClient, times(1)).checkTransaction(any());
        verify(fulfillmentService, times(1)).fulfillOrder(order.getId());
    }

    @Test
    @DisplayName("Pending transaction keeps payment status PENDING and does not fulfill")
    void pendingTransactionRemainsPending() {
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(1, "Transaction not found", null));

        PaymentStatusResponse response = verificationService.verifyPayment(payment.getId(), customer.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        Order updatedOrder = orders.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        verify(fulfillmentService, never()).fulfillOrder(any());
    }

    @Test
    @DisplayName("Expired unpaid payment transitions to EXPIRED and cancels the order")
    void expiredPaymentTransitionsToExpired() {
        payment.setExpiresAt(Instant.now().minusSeconds(10));
        payment = payments.save(payment);

        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(1, "Transaction not found", null));

        PaymentStatusResponse response = verificationService.verifyPayment(payment.getId(), customer.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.EXPIRED);
        Order updatedOrder = orders.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        verify(fulfillmentService, never()).fulfillOrder(any());
    }

    @Test
    @DisplayName("Late confirmation for an expired payment is safely honored if transaction succeeded")
    void latePaymentConfirmationHonored() {
        payment.setExpiresAt(Instant.now().minusSeconds(10));
        payment = payments.save(payment);

        String txHash = "hash-late-" + UUID.randomUUID();
        BakongTransactionData data = new BakongTransactionData(
                txHash,
                "customer@devb",
                "merchant@bank",
                "USD",
                new BigDecimal("4.25"),
                "Order description",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "EXT-002"
        );
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        PaymentStatusResponse response = verificationService.verifyPayment(payment.getId(), customer.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        Order updatedOrder = orders.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(fulfillmentService, times(1)).fulfillOrder(order.getId());
    }

    @Test
    @DisplayName("Amount mismatch marks payment and order as NEEDS_REVIEW without fulfilling")
    void amountMismatchRequiresReview() {
        String txHash = "hash-" + UUID.randomUUID();
        BakongTransactionData data = new BakongTransactionData(
                txHash,
                "customer@devb",
                "merchant@bank",
                "USD",
                new BigDecimal("1.00"), // Mismatched amount! Expected 4.25
                "Order description",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "EXT-003"
        );
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        PaymentStatusResponse response = verificationService.verifyPayment(payment.getId(), customer.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        Order updatedOrder = orders.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);

        verify(fulfillmentService, never()).fulfillOrder(any());
    }

    @Test
    @DisplayName("Currency mismatch marks payment and order as NEEDS_REVIEW without fulfilling")
    void currencyMismatchRequiresReview() {
        String txHash = "hash-" + UUID.randomUUID();
        BakongTransactionData data = new BakongTransactionData(
                txHash,
                "customer@devb",
                "merchant@bank",
                "KHR", // Mismatched currency! Expected USD
                new BigDecimal("4.25"),
                "Order description",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "EXT-004"
        );
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        PaymentStatusResponse response = verificationService.verifyPayment(payment.getId(), customer.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        Order updatedOrder = orders.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);

        verify(fulfillmentService, never()).fulfillOrder(any());
    }

    @Test
    @DisplayName("Bakong numeric currency code (840) is accepted as USD")
    void numericCurrencyCodeIsAccepted() {
        String txHash = "hash-" + UUID.randomUUID();
        BakongTransactionData data = new BakongTransactionData(
                txHash,
                "customer@devb",
                "merchant@bank",
                "840",
                new BigDecimal("4.25"),
                "Order description",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "EXT-004B"
        );
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        PaymentStatusResponse response = verificationService.verifyPayment(payment.getId(), customer.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        Order updatedOrder = orders.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(fulfillmentService, times(1)).fulfillOrder(order.getId());
    }

    @Test
    @DisplayName("Recipient account mismatch marks payment and order as NEEDS_REVIEW without fulfilling")
    void recipientAccountMismatchRequiresReview() {
        String txHash = "hash-" + UUID.randomUUID();
        BakongTransactionData data = new BakongTransactionData(
                txHash,
                "customer@devb",
                "wrong_merchant@bank", // Mismatched recipient account!
                "USD",
                new BigDecimal("4.25"),
                "Order description",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "EXT-005"
        );
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        PaymentStatusResponse response = verificationService.verifyPayment(payment.getId(), customer.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        Order updatedOrder = orders.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);

        verify(fulfillmentService, never()).fulfillOrder(any());
    }

    @Test
    @DisplayName("Missing recipient account in provider response is rejected (fail closed)")
    void missingRecipientAccountRequiresReview() {
        String txHash = "hash-" + UUID.randomUUID();
        BakongTransactionData data = new BakongTransactionData(
                txHash,
                "customer@devb",
                null, // Bakong returned no receiving account
                "USD",
                new BigDecimal("4.25"),
                "Order description",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "EXT-005B"
        );
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        PaymentStatusResponse response = verificationService.verifyPayment(payment.getId(), customer.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        Order updatedOrder = orders.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);

        verify(fulfillmentService, never()).fulfillOrder(any());
    }

    @Test
    @DisplayName("Duplicate transaction hash (replay attack) is rejected with NEEDS_REVIEW")
    void replayAttackDetection() {
        // Create an existing payment with this hash
        String duplicateHash = "duplicate-tx-hash-001";
        Payment priorPayment = new Payment();
        priorPayment.setOrderId(UUID.randomUUID());
        priorPayment.setProvider(PaymentProvider.BAKONG);
        priorPayment.setReference("PRIOR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
        priorPayment.setGatewayReference(duplicateHash);
        priorPayment.setBakongHash(duplicateHash);
        priorPayment.setStatus(PaymentStatus.COMPLETED);
        priorPayment.setAmount(new BigDecimal("4.25"));
        priorPayment.setCurrency("USD");
        payments.save(priorPayment);

        BakongTransactionData data = new BakongTransactionData(
                duplicateHash,
                "customer@devb",
                "merchant@bank",
                "USD",
                new BigDecimal("4.25"),
                "Order description",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "EXT-006"
        );
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        PaymentStatusResponse response = verificationService.verifyPayment(payment.getId(), customer.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        Order updatedOrder = orders.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);

        verify(fulfillmentService, never()).fulfillOrder(order.getId());
    }

    @Test
    @DisplayName("Verification respects per-payment cooldown and throws 429 when throttled")
    void cooldownThrottling() {
        payment.setLastProviderCheckAt(Instant.now());
        payment = payments.save(payment);

        assertThatThrownBy(() -> verificationService.verifyPayment(payment.getId(), customer.getId()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.TOO_MANY_REQUESTS)
                .hasMessageContaining("cooldown");

        verify(apiClient, never()).checkTransaction(any());
    }

    @Test
    @DisplayName("Provider timeout leaves the payment PENDING and never fulfils")
    void providerTimeoutIsHandledSafely() {
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenThrow(new PaymentGatewayException("bakong",
                        "Timeout or connection error reading Bakong API"));

        assertThatThrownBy(() -> verificationService.verifyPayment(payment.getId(), customer.getId()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_GATEWAY);

        Payment unchanged = payments.findById(payment.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(unchanged.getLastProviderCheckAt()).isNotNull();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);

        verify(fulfillmentService, never()).fulfillOrder(any());
    }

    @Test
    @DisplayName("Concurrent verification completes the payment and fulfils it once")
    void concurrentVerificationFulfilsOnce() throws Exception {
        String txHash = "hash-concurrent-" + UUID.randomUUID();
        BakongTransactionData data = new BakongTransactionData(
                txHash,
                "customer@devb",
                "merchant@bank",
                "USD",
                new BigDecimal("4.25"),
                "Order description",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "EXT-CONCURRENT"
        );
        when(apiClient.checkTransaction(payment.getQrMd5()))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PaymentStatusResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return verificationService.verifyPayment(payment.getId(), customer.getId());
                }));
            }
            start.countDown();
            for (Future<PaymentStatusResponse> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.COMPLETED);
            }
        } finally {
            pool.shutdownNow();
        }

        Payment updated = payments.findById(payment.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(updated.getBakongHash()).isEqualTo(txHash);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);

        verify(fulfillmentService, times(1)).fulfillOrder(order.getId());
    }

    private User createUser(String roleName) {
        Role role = roles.findByName(roleName).orElseGet(() -> {
            Role r = new Role();
            r.setName(roleName);
            return roles.save(r);
        });
        User user = new User();
        user.setFullName("Payment Tester");
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("hashed_password");
        user.setAccountStatus(AccountStatus.ACTIVE);
        user = users.save(user);
        user.addRole(role);
        return users.save(user);
    }
}
