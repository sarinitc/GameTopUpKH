package org.example.gametopupkh.payment.bakong;

import java.math.BigDecimal;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.example.gametopupkh.fulfillment.FulfillmentService;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.order.OrderRequest;
import org.example.gametopupkh.order.OrderResponse;
import org.example.gametopupkh.order.OrderService;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.payment.PaymentRepository;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckAccountResponse;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse.BakongTransactionData;
import org.example.gametopupkh.security.JwtService;
import org.example.gametopupkh.supplier.SupplierTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:bakong-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=bakong-api-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.client=mock", "fulfillment.worker.enabled=false",
        "payment.reconcile.cooldown=PT0S",
        "payment.bakong.environment=sandbox",
        "payment.bakong.base-url=https://sit-api-bakong.nbc.org.kh",
        "payment.bakong.account-id=merchant@bank"
})
@AutoConfigureMockMvc
class BakongPaymentApiTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderService orderService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private BakongApiClient apiClient;
    @MockitoBean private FulfillmentService fulfillmentService;

    private UUID customerId;
    private UUID otherCustomerId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        customerId = createUser("USER");
        otherCustomerId = createUser("USER");
        orderId = createOrder(customerId);
    }

    @Test
    @DisplayName("Creates Bakong KHQR payment via /api/v1/orders/{orderId}/payments/bakong")
    void createBakongPaymentViaOrderEndpoint() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments/bakong")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.provider").value("BAKONG"))
                .andExpect(jsonPath("$.data.qrString").isNotEmpty())
                .andExpect(jsonPath("$.data.qrMd5").value(matchesPattern("^[a-f0-9]{32}$")))
                .andExpect(jsonPath("$.data.reference").value(matchesPattern("[A-Za-z0-9]{20}")))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andReturn();

        String paymentId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.paymentId");
        assertThat(paymentId).isNotNull();

        // Repeating returns the same payment (idempotent)
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments/bakong")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId));
    }

    @Test
    @DisplayName("Creates Bakong KHQR payment via /api/v1/payments/bakong/khqr")
    void createBakongPaymentViaKhqrEndpoint() throws Exception {
        String body = """
                {
                    "orderId": "%s"
                }
                """.formatted(orderId);

        mockMvc.perform(post("/api/v1/payments/bakong/khqr")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.provider").value("BAKONG"))
                .andExpect(jsonPath("$.data.qrMd5").value(matchesPattern("^[a-f0-9]{32}$")));
    }

    @Test
    @DisplayName("Cannot create payment for an order owned by someone else")
    void nonOwnerCannotCreatePayment() throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments/bakong")
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherCustomerId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Verifies Bakong payment successfully and marks order PAID")
    void verifyPaymentSucceedsAndMarksOrderPaid() throws Exception {
        // Create payment
        MvcResult createResult = mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments/bakong")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isCreated())
                .andReturn();

        String paymentId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.data.paymentId");
        String md5 = JsonPath.read(createResult.getResponse().getContentAsString(), "$.data.qrMd5");

        String txHash = "hash-" + UUID.randomUUID();
        BakongTransactionData data = new BakongTransactionData(
                txHash,
                "customer@devb",
                "merchant@bank",
                "USD",
                new BigDecimal("4.25"),
                "Order",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "EXT-99"
        );
        when(apiClient.checkTransaction(md5))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/verify")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.gatewayReference").value(txHash));

        verify(fulfillmentService).fulfillOrder(orderId);
    }

    @Test
    @DisplayName("Admin can verify the configured Bakong merchant account")
    void adminCanVerifyMerchantAccount() throws Exception {
        UUID adminId = createUser("ADMIN");
        when(apiClient.checkAccount("merchant@bank"))
                .thenReturn(new BakongCheckAccountResponse(0, "Account ID exists",
                        new BakongCheckAccountResponse.BakongAccountData("ACTIVATED", 0, "FULL_KYC", true)));

        mockMvc.perform(get("/api/v1/admin/payments/bakong/account")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value("merchant@bank"))
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.responseCode").value(0))
                .andExpect(jsonPath("$.data.canReceive").value(true))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVATED"))
                .andExpect(jsonPath("$.data.kycStatus").value("FULL_KYC"));

        verify(apiClient).checkAccount("merchant@bank");
    }

    @Test
    @DisplayName("Customers cannot run the admin Bakong account verification")
    void customerCannotVerifyMerchantAccount() throws Exception {
        mockMvc.perform(get("/api/v1/admin/payments/bakong/account")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isForbidden());

        verify(apiClient, never()).checkAccount(any());
    }

    private static final String PASSWORD = "secret123";

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
            Role r = new Role();
            r.setName(roleName);
            return roleRepository.save(r);
        });
        User user = new User();
        user.setFullName("Test User");
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(user);
        user.addRole(role);
        userRepository.save(user);
        return user.getId();
    }

    private UUID createOrder(UUID ownerId) {
        var product = SupplierTestFixtures.createEligibleProduct(entityManager, transactionManager);
        OrderRequest request = new OrderRequest(
                product.getPackageId(),
                "player-123",
                "zone-1",
                null,
                null
        );
        OrderResponse response = orderService.create(ownerId, request);
        return response.id();
    }
}
