package org.example.gametopupkh.refund;

import java.math.BigDecimal;
import java.util.UUID;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.fulfillment.Fulfillment;
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
import org.example.gametopupkh.payment.PaymentProvider;
import org.example.gametopupkh.payment.PaymentRepository;
import org.example.gametopupkh.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:refund-service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=refund-service-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "fulfillment.worker.enabled=false"
})
class RefundServiceTests {

    @Autowired private RefundService service;
    @Autowired private RefundRepository refunds;
    @Autowired private FulfillmentRepository fulfillments;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderRepository orders;
    @Autowired private OrderStatusHistoryRepository history;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;

    private User customer;
    private User admin;
    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        refunds.deleteAll();
        customer = createUser("USER");
        admin = createUser("ADMIN");
        order = new Order();
        order.setOrderNumber("REF-" + UUID.randomUUID());
        order.setUser(customer);
        order.setPackageId(UUID.randomUUID());
        order.setPlayerId("player-1");
        order.setGameNameSnapshot("Game");
        order.setPackageNameSnapshot("Package");
        order.setUnitPrice(new BigDecimal("9.99"));
        order.setTotalAmount(new BigDecimal("9.99"));
        order.setCurrency("USD");
        order.setStatus(OrderStatus.COMPLETED);
        order = orders.save(order);

        payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setProvider(PaymentProvider.MOCK);
        payment.setReference("REFPAY" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setAmount(new BigDecimal("9.99"));
        payment.setCurrency("USD");
        payment = payments.save(payment);
    }

    @Test
    void onlyAdminCanRequestRefunds() {
        assertThatThrownBy(() -> service.requestRefund(payment.getId(), customer.getId(), null))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void mockRefundCompletesAndMovesOrderToRefunded() {
        RefundResponse refund = service.requestRefund(payment.getId(), admin.getId(),
                new RefundRequest(null, "Customer request"));

        assertThat(refund.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.providerReference()).startsWith("MOCK-REFUND-");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(history.findAllByOrderIdOrderByChangedAtAsc(order.getId()))
                .extracting(org.example.gametopupkh.order.OrderStatusHistory::getStatus)
                .contains(OrderStatus.REFUNDED);
    }

    @Test
    void refundIsIdempotentPerPayment() {
        RefundResponse first = service.requestRefund(payment.getId(), admin.getId(), null);
        RefundResponse second = service.requestRefund(payment.getId(), admin.getId(), null);

        assertThat(first.refundId()).isEqualTo(second.refundId());
        assertThat(refunds.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PROCESSING", "NEEDS_REVIEW"})
    void unresolvedDeliveryPreventsRefundCreation(OrderStatus orderStatus) {
        order.setStatus(orderStatus);
        order = orders.saveAndFlush(order);
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.setOrderId(order.getId());
        fulfillment.setStatus(FulfillmentStatus.PROCESSING);
        fulfillments.saveAndFlush(fulfillment);

        assertThatThrownBy(() -> service.requestRefund(payment.getId(), admin.getId(), null))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getCode()).isEqualTo("DELIVERY_UNRESOLVED");
                });

        assertThat(refunds.findByPaymentId(payment.getId())).isEmpty();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(orderStatus);
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void cannotRefundUncompletedOrAlreadyRefundedPayment() {
        payment.setStatus(PaymentStatus.PENDING);
        payment = payments.saveAndFlush(payment);
        assertThatThrownBy(() -> service.requestRefund(payment.getId(), admin.getId(), null))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        payment.setStatus(PaymentStatus.COMPLETED);
        payment = payments.saveAndFlush(payment);
        order.setStatus(OrderStatus.REFUNDED);
        order = orders.saveAndFlush(order);
        assertThatThrownBy(() -> service.requestRefund(payment.getId(), admin.getId(), null))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void partialRefundIsPendingForAbaAndDoesNotChangeOrderStatus() {
        payment.setProvider(PaymentProvider.ABA);
        payment.setReference("ABAPAY" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        payments.save(payment);
        order.setStatus(OrderStatus.COMPLETED);
        orders.save(order);

        RefundResponse refund = service.requestRefund(payment.getId(), admin.getId(),
                new RefundRequest(new BigDecimal("5.00"), "Partial refund"));

        assertThat(refund.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(refund.amount()).isEqualByComparingTo("5.00");
        assertThat(refund.providerReference()).isNull();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void customerCanOnlyViewTheirOwnOrderRefunds() {
        service.requestRefund(payment.getId(), admin.getId(), null);
        User other = createUser("USER");

        assertThat(service.findByOrder(order.getId(), customer.getId(), false)).hasSize(1);
        assertThatThrownBy(() -> service.findByOrder(order.getId(), other.getId(), false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(service.findByOrder(order.getId(), admin.getId(), true)).hasSize(1);
    }

    private User createUser(String roleName) {
        Role role = roles.findByName(roleName).orElseGet(() -> {
            Role value = new Role();
            value.setName(roleName);
            return roles.save(value);
        });
        User user = new User();
        user.setFullName("Refund Tester");
        user.setEmail("refund-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("unused-password-hash");
        user.setAccountStatus(AccountStatus.ACTIVE);
        user = users.save(user);
        user.addRole(role);
        return users.save(user);
    }
}
