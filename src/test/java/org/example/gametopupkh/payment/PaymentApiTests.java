package org.example.gametopupkh.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.example.gametopupkh.catalog.game.entity.Game;
import org.example.gametopupkh.catalog.game.repository.GameRepository;
import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.catalog.packagecatalog.repository.GamePackageRepository;
import org.example.gametopupkh.fulfillment.Fulfillment;
import org.example.gametopupkh.fulfillment.FulfillmentRepository;
import org.example.gametopupkh.fulfillment.FulfillmentStatus;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.order.OrderRequest;
import org.example.gametopupkh.order.OrderResponse;
import org.example.gametopupkh.order.OrderService;
import org.example.gametopupkh.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:payment-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=payment-api-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.client=mock", "supplier.mock.fail=false",
        "fulfillment.worker.enabled=false"
})
@AutoConfigureMockMvc
class PaymentApiTests {

    private static final String PASSWORD = "secret123";
    private static final String USER_ROLE = "USER";
    private static final String ADMIN_ROLE = "ADMIN";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private FulfillmentRepository fulfillmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private GamePackageRepository gamePackageRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void creatingAPaymentIsIdempotentForTheOwner() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID orderId = createOrder(customerId);

        MvcResult first = mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments/aba")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.reference").value(matchesPattern("[A-Za-z0-9]{20}")))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andReturn();

        String paymentId = JsonPath.read(first.getResponse().getContentAsString(), "$.data.paymentId");
        String reference = JsonPath.read(first.getResponse().getContentAsString(), "$.data.reference");

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments/aba")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId))
                .andExpect(jsonPath("$.data.reference").value(reference));
    }

    @Test
    void paymentReadsAreLimitedToTheOwnerOrAnAdmin() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID otherId = createUser(USER_ROLE);
        UUID adminId = createUser(ADMIN_ROLE);
        UUID orderId = createOrder(customerId);
        String paymentId = createPayment(customerId, orderId);

        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId));

        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId));
    }

    @Test
    void thePublicCallbackCompletesThePaymentAndOrder() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID orderId = createOrder(customerId);
        String paymentId = createPayment(customerId, orderId);
        String reference = JsonPath.read(mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andReturn().getResponse().getContentAsString(), "$.data.reference");

        mockMvc.perform(post("/api/v1/payments/aba/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"" + reference
                                + "\",\"event_id\":\"evt-1\",\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        org.assertj.core.api.Assertions.assertThat(
                        orderService.findById(orderId, customerId, false).status())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void onlyAnAdminCanReconcile() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID adminId = createUser(ADMIN_ROLE);
        UUID orderId = createOrder(customerId);
        String paymentId = createPayment(customerId, orderId);

        mockMvc.perform(post("/api/v1/admin/payments/" + paymentId + "/reconcile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/admin/payments/" + paymentId + "/reconcile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void reconciliationIsThrottledAndAuditedPerPayment() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID adminId = createUser(ADMIN_ROLE);
        UUID orderId = createOrder(customerId);
        String paymentId = createPayment(customerId, orderId);

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        payment.setLastProviderCheckAt(Instant.now());
        paymentRepository.save(payment);

        mockMvc.perform(post("/api/v1/admin/payments/" + paymentId + "/reconcile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PAYMENT_RECONCILIATION_THROTTLED"));

        org.assertj.core.api.Assertions.assertThat(
                        paymentEventRepository.findAllByPaymentIdOrderByCreatedAtAsc(payment.getId()))
                .anyMatch(event -> "RECONCILE_THROTTLED".equals(event.getType())
                        && adminId.equals(event.getActorUserId()));
    }

    @Test
    void mockSuccessEndpointCompletesThePaymentAndOrder() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID orderId = createOrder(customerId);
        String paymentId = createPayment(customerId, orderId);

        mockMvc.perform(post("/api/v1/dev/payments/" + paymentId + "/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/dev/payments/" + paymentId + "/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        org.assertj.core.api.Assertions.assertThat(
                        orderService.findById(orderId, customerId, false).status())
                .isEqualTo(OrderStatus.COMPLETED);

        Payment payment = paymentRepository.findById(UUID.fromString(paymentId)).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(
                        paymentEventRepository.findAllByPaymentIdOrderByCreatedAtAsc(payment.getId()))
                .anyMatch(event -> "MOCK_SUCCESS".equals(event.getType()));

        Fulfillment fulfillment = fulfillmentRepository.findByOrderId(orderId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(fulfillment.getStatus())
                .isEqualTo(FulfillmentStatus.SUCCEEDED);
        org.assertj.core.api.Assertions.assertThat(fulfillment.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void mockConfirmUrlByGatewayReferenceCompletesThePayment() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID orderId = createOrder(customerId);
        String paymentId = createPayment(customerId, orderId);
        String reference = JsonPath.read(mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andReturn().getResponse().getContentAsString(), "$.data.gatewayReference");

        mockMvc.perform(post("/api/v1/payments/mock/" + reference + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void mockSuccessEndpointsArePublishedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/v1/dev/payments/{paymentId}/success']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/payments/mock/{reference}/confirm']").exists());
    }

    @Test
    void paymentEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + UUID.randomUUID() + "/payments/aba"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/payments/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    private String createPayment(UUID customerId, UUID orderId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments/aba")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.paymentId");
    }

    private UUID createOrder(UUID customerId) {
        Game game = new Game();
        game.setName("Payment Test Game");
        game.setSlug("payment-test-" + UUID.randomUUID().toString().substring(0, 8));
        game.setPlayerIdLabel("Player ID");
        game.setRequiresZoneId(false);
        game.setActive(true);
        game = gameRepository.save(game);

        GamePackage gamePackage = new GamePackage();
        gamePackage.setGameId(game.getId());
        gamePackage.setName("60 Diamonds");
        gamePackage.setSku("PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        gamePackage.setDiamondAmount(60);
        gamePackage.setPrice(new BigDecimal("9.99"));
        gamePackage.setCurrency("USD");
        gamePackage.setActive(true);
        gamePackage = gamePackageRepository.save(gamePackage);

        OrderResponse order = orderService.create(customerId,
                new OrderRequest(gamePackage.getId(), "Player-1", null, null, null));
        return order.id();
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
        user.setFullName("Test User");
        user.setEmail("payment-user-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(user);
        user.addRole(role);
        userRepository.save(user);
        return user.getId();
    }
}
