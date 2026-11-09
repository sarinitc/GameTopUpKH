package org.example.gametopupkh.payment.bakong;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderRequest;
import org.example.gametopupkh.order.OrderResponse;
import org.example.gametopupkh.order.OrderService;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.payment.PaymentRepository;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckAccountResponse;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A production deployment with live Bakong payments disabled must reject new
 * KHQR requests without leaving a half-created payment behind, while the
 * read-only merchant-account check stays available for pre-flight validation.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:bakong-disabled;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=bakong-disabled-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.client=mock", "fulfillment.worker.enabled=false",
        "payment.reconciliation.enabled=false",
        "payment.bakong.environment=production",
        "payment.bakong.enabled=false",
        "payment.bakong.base-url=https://api-bakong.nbc.gov.kh",
        "payment.bakong.api-token=production-token",
        "payment.bakong.account-id=merchant@bank"
})
@AutoConfigureMockMvc
class BakongDisabledPaymentApiTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private BakongApiClient apiClient;

    private UUID customerId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        customerId = createUser("USER");
        var product = SupplierTestFixtures.createEligibleProduct(entityManager, transactionManager);
        OrderResponse response = orderService.create(customerId, new OrderRequest(
                product.getPackageId(), "player-123", "zone-1", null, null));
        orderId = response.id();
    }

    @Test
    @DisplayName("Production with live payments disabled returns 503 without reserving a payment")
    void disabledProductionDoesNotCreateAPayment() throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments/bakong")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("BAKONG_PAYMENT_DISABLED"))
                .andExpect(jsonPath("$.message").value("Live Bakong payments are disabled by configuration"));

        assertThat(payments.findByOrderId(orderId)).isEmpty();
        assertThat(orders.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("The direct KHQR endpoint is gated the same way")
    void disabledProductionRejectsTheDirectKhqrEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/payments/bakong/khqr")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BAKONG_PAYMENT_DISABLED"));

        assertThat(payments.findByOrderId(orderId)).isEmpty();
    }

    @Test
    @DisplayName("Admin can verify the merchant account while live payments are disabled")
    void accountCheckWorksBeforeLivePaymentsAreEnabled() throws Exception {
        UUID adminId = createUser("ADMIN");
        when(apiClient.checkAccount("merchant@bank"))
                .thenReturn(new BakongCheckAccountResponse(0, "Account ID exists",
                        new BakongCheckAccountResponse.BakongAccountData("ACTIVATED", 0, "FULL_KYC", true)));

        mockMvc.perform(get("/api/v1/admin/payments/bakong/account")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.responseCode").value(0))
                .andExpect(jsonPath("$.data.canReceive").value(true))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVATED"))
                .andExpect(jsonPath("$.data.kycStatus").value("FULL_KYC"));
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
            Role value = new Role();
            value.setName(roleName);
            return roleRepository.save(value);
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
}
