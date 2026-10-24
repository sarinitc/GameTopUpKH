package org.example.gametopupkh.refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.example.gametopupkh.catalog.game.entity.Game;
import org.example.gametopupkh.catalog.game.repository.GameRepository;
import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.catalog.packagecatalog.repository.GamePackageRepository;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderRequest;
import org.example.gametopupkh.order.OrderResponse;
import org.example.gametopupkh.order.OrderService;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.order.OrderStatusHistoryRepository;
import org.example.gametopupkh.payment.Payment;
import org.example.gametopupkh.payment.PaymentProvider;
import org.example.gametopupkh.payment.PaymentRepository;
import org.example.gametopupkh.payment.PaymentService;
import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.payment.dto.CreatePaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:refund-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=refund-api-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.client=mock", "supplier.mock.fail=false",
        "fulfillment.worker.enabled=false"
})
@AutoConfigureMockMvc
class RefundApiTests {

    private static final String PASSWORD = "secret123";

    @Autowired private MockMvc mockMvc;
    @Autowired private RefundRepository refunds;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentService paymentService;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orders;
    @Autowired private OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private GameRepository gameRepository;
    @Autowired private GamePackageRepository gamePackageRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearRefunds() {
        refunds.deleteAll();
    }

    @Test
    void onlyAdminCanRequestARefundAndSupportCanReadIt() throws Exception {
        UUID customerId = createUser("USER");
        UUID adminId = createUser("ADMIN");
        UUID supportId = createUser("SUPPORT");
        Purchase purchase = purchase(customerId);

        mockMvc.perform(post("/api/v1/admin/payments/" + purchase.paymentId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Customer request\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/admin/payments/" + purchase.paymentId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supportId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post(
                                "/api/v1/admin/payments/" + purchase.paymentId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Customer request\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.paymentId").value(purchase.paymentId().toString()))
                .andExpect(jsonPath("$.data.orderId").value(purchase.orderId().toString()))
                .andExpect(jsonPath("$.data.amount").value(9.99))
                .andExpect(jsonPath("$.data.reason").value("Customer request"))
                .andReturn();
        String refundId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.refundId");

        mockMvc.perform(post("/api/v1/admin/payments/" + purchase.paymentId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.refundId").value(refundId));
        assertThat(refunds.count()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/admin/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supportId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].refundId").value(refundId));

        mockMvc.perform(get("/api/v1/admin/refunds/" + refundId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(supportId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundId").value(refundId))
                .andExpect(jsonPath("$.data.requestedBy").value(adminId.toString()));

        assertThat(orderService.findById(purchase.orderId(), customerId, false).status())
                .isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void customersSeeOnlyRefundsForTheirOwnOrders() throws Exception {
        UUID customerId = createUser("USER");
        UUID otherId = createUser("USER");
        UUID adminId = createUser("ADMIN");
        Purchase purchase = purchase(customerId);
        MvcResult created = mockMvc.perform(post(
                                "/api/v1/admin/payments/" + purchase.paymentId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isCreated())
                .andReturn();
        String refundId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.refundId");

        mockMvc.perform(get("/api/v1/orders/" + purchase.orderId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].refundId").value(refundId));

        mockMvc.perform(get("/api/v1/orders/" + purchase.orderId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/orders/" + purchase.orderId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void abaRefundStaysPendingAndDoesNotMarkTheOrderRefunded() throws Exception {
        UUID customerId = createUser("USER");
        UUID adminId = createUser("ADMIN");
        UUID orderId = createOrder(customerId, OrderStatus.COMPLETED);

        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setProvider(PaymentProvider.ABA);
        payment.setReference("ABA" + UUID.randomUUID().toString().replace("-", "").substring(0, 15));
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setAmount(new BigDecimal("9.99"));
        payment.setCurrency("USD");
        payment.setPaidAt(Instant.now());
        payment = payments.save(payment);

        mockMvc.perform(post("/api/v1/admin/payments/" + payment.getId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5.00,\"reason\":\"Partial refund\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.amount").value(5.00))
                .andExpect(jsonPath("$.data.providerReference").isEmpty());

        assertThat(orderService.findById(orderId, customerId, false).status())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void refundValidationAndAuthenticationAreEnforced() throws Exception {
        UUID customerId = createUser("USER");
        UUID adminId = createUser("ADMIN");
        Purchase purchase = purchase(customerId);

        mockMvc.perform(get("/api/v1/admin/refunds"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/payments/" + purchase.paymentId() + "/refunds"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/payments/" + UUID.randomUUID() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/admin/payments/" + purchase.paymentId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFUND_AMOUNT_EXCEEDED"));

        Payment pending = new Payment();
        OrderResponse order = orderService.create(customerId, orderRequest());
        pending.setOrderId(order.id());
        pending.setProvider(PaymentProvider.MOCK);
        pending.setReference("PEND" + UUID.randomUUID().toString().replace("-", "").substring(0, 15));
        pending.setStatus(PaymentStatus.PENDING);
        pending.setAmount(new BigDecimal("9.99"));
        pending.setCurrency("USD");
        pending = payments.save(pending);

        mockMvc.perform(post("/api/v1/admin/payments/" + pending.getId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_REFUNDABLE"));
    }

    private Purchase purchase(UUID customerId) {
        OrderResponse order = orderService.create(customerId, orderRequest());
        CreatePaymentResponse payment = paymentService.createAbaPayment(order.id(), customerId);
        paymentService.confirmMockPayment(payment.paymentId().toString());
        return new Purchase(order.id(), payment.paymentId());
    }

    private UUID createOrder(UUID customerId, OrderStatus status) {
        UUID orderId = orderService.create(customerId, orderRequest()).id();
        if (status != OrderStatus.PENDING_PAYMENT) {
            Order order = orders.findById(orderId).orElseThrow();
            order.setStatus(status);
            orders.saveAndFlush(order);
            if (status == OrderStatus.COMPLETED) {
                org.example.gametopupkh.order.OrderStatusHistory history =
                        new org.example.gametopupkh.order.OrderStatusHistory();
                history.setOrderId(orderId);
                history.setStatus(OrderStatus.PAID);
                history.setNote("Payment completed");
                orderStatusHistoryRepository.saveAndFlush(history);
                history = new org.example.gametopupkh.order.OrderStatusHistory();
                history.setOrderId(orderId);
                history.setStatus(OrderStatus.PROCESSING);
                history.setNote("Fulfillment started");
                orderStatusHistoryRepository.saveAndFlush(history);
                history = new org.example.gametopupkh.order.OrderStatusHistory();
                history.setOrderId(orderId);
                history.setStatus(OrderStatus.COMPLETED);
                history.setNote("Fulfillment completed");
                orderStatusHistoryRepository.saveAndFlush(history);
            }
        }
        return orderId;
    }

    private OrderRequest orderRequest() {
        Game game = new Game();
        game.setName("Refund Test Game");
        game.setSlug("refund-test-" + UUID.randomUUID().toString().substring(0, 8));
        game.setPlayerIdLabel("Player ID");
        game.setRequiresZoneId(false);
        game.setActive(true);
        game = gameRepository.save(game);

        GamePackage gamePackage = new GamePackage();
        gamePackage.setGameId(game.getId());
        gamePackage.setName("60 Diamonds");
        gamePackage.setSku("REF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        gamePackage.setDiamondAmount(60);
        gamePackage.setPrice(new BigDecimal("9.99"));
        gamePackage.setCurrency("USD");
        gamePackage.setActive(true);
        gamePackage = gamePackageRepository.save(gamePackage);

        return new OrderRequest(gamePackage.getId(), "Player-1", null, null, null);
    }

    private String bearer(UUID userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + emailOf(userId) + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
    }

    private String emailOf(UUID userId) {
        return userRepository.findById(userId).orElseThrow().getEmail();
    }

    private UUID createUser(String roleName) {
        Role role = roleRepository.findByName(roleName).orElseGet(() -> {
            Role created = new Role();
            created.setName(roleName);
            return roleRepository.save(created);
        });
        User user = new User();
        user.setFullName("Refund API Tester");
        user.setEmail("refund-api-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(user);
        user.addRole(role);
        userRepository.save(user);
        return user.getId();
    }

    private record Purchase(UUID orderId, UUID paymentId) {
    }
}
