package org.example.gametopupkh.order;

import java.math.BigDecimal;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.example.gametopupkh.catalog.game.entity.Game;
import org.example.gametopupkh.catalog.game.repository.GameRepository;
import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.catalog.packagecatalog.repository.GamePackageRepository;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderApiTests {

    private static final String PASSWORD = "secret123";
    private static final String USER_ROLE = "USER";
    private static final String ADMIN_ROLE = "ADMIN";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderService orderService;

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
    void createOrderCopiesPriceAndNamesFromTheCatalog() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("12.50"));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gamePackage.getId(), "Player-1", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderNumber").value(matchesPattern("ORD-\\d{8}-[0-9A-F]{8}")))
                .andExpect(jsonPath("$.data.packageId").value(gamePackage.getId().toString()))
                .andExpect(jsonPath("$.data.playerId").value("Player-1"))
                .andExpect(jsonPath("$.data.gameName").value(game.getName()))
                .andExpect(jsonPath("$.data.packageName").value(gamePackage.getName()))
                .andExpect(jsonPath("$.data.unitPrice").value(12.50))
                .andExpect(jsonPath("$.data.totalAmount").value(12.50))
                .andExpect(jsonPath("$.data.quantity").value(1))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.history", hasSize(1)))
                .andExpect(jsonPath("$.data.history[0].status").value("PENDING_PAYMENT"));
    }

    @Test
    void createOrderRejectsUnknownPackage() throws Exception {
        UUID customerId = createUser(USER_ROLE);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), "Player-1", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PACKAGE_NOT_FOUND"));
    }

    @Test
    void createOrderRejectsInactivePackage() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("5.00"));
        gamePackage.setActive(false);
        gamePackageRepository.save(gamePackage);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gamePackage.getId(), "Player-1", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PACKAGE_INACTIVE"));
    }

    @Test
    void createOrderRejectsInactiveGame() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("5.00"));
        game.setActive(false);
        gameRepository.save(game);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gamePackage.getId(), "Player-1", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GAME_INACTIVE"));
    }

    @Test
    void createOrderRequiresAZoneIdWhenTheGameNeedsOne() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        Game game = createGame(true);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gamePackage.getId(), "Player-1", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ZONE_ID_REQUIRED"));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gamePackage.getId(), "Player-1", "Zone-7")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.zoneId").value("Zone-7"));
    }

    @Test
    void createOrderDropsTheZoneIdWhenTheGameDoesNotNeedOne() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gamePackage.getId(), "Player-1", "Zone-7")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.zoneId").isEmpty());
    }

    @Test
    void createOrderRejectsABlankPlayerId() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gamePackage.getId(), "   ", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createOrderRejectsAnInactiveAccount() {
        UUID customerId = createUser(USER_ROLE, AccountStatus.SUSPENDED);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));

        assertThatThrownBy(() -> orderService.create(customerId,
                new OrderRequest(gamePackage.getId(), "Player-1", null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");
    }

    @Test
    void createOrderRequiresAuthentication() throws Exception {
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gamePackage.getId(), "Player-1", null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void listMyOrdersReturnsOnlyTheOrdersOfTheCaller() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID otherId = createUser(USER_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));

        String ownOrderId = createOrder(customerId, gamePackage);
        createOrder(otherId, gamePackage);

        mockMvc.perform(get("/api/v1/orders/my")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(ownOrderId))
                .andExpect(jsonPath("$.data[0].history", hasSize(1)));
    }

    @Test
    void getOrderReturnsTheOrderOfTheOwner() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));
        String orderId = createOrder(customerId, gamePackage);

        mockMvc.perform(get("/api/v1/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"));
    }

    @Test
    void getOrderHidesTheOrderOfAnotherCustomer() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID otherId = createUser(USER_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));
        String orderId = createOrder(otherId, gamePackage);

        mockMvc.perform(get("/api/v1/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getOrderReturnsNotFoundForUnknownId() throws Exception {
        UUID customerId = createUser(USER_ROLE);

        mockMvc.perform(get("/api/v1/orders/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void adminCanReadAnyOrderThroughTheCustomerEndpoint() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID adminId = createUser(ADMIN_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));
        String orderId = createOrder(customerId, gamePackage);

        mockMvc.perform(get("/api/v1/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId));
    }

    @Test
    void adminListsAllOrders() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID adminId = createUser(ADMIN_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));
        String orderId = createOrder(customerId, gamePackage);

        mockMvc.perform(get("/api/v1/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + orderId + "')]").isNotEmpty());

        mockMvc.perform(get("/api/v1/admin/orders?status=PENDING_PAYMENT")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + orderId + "')]").isNotEmpty());

        mockMvc.perform(get("/api/v1/admin/orders?status=COMPLETED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + orderId + "')]").isEmpty());
    }

    @Test
    void adminReadsAnyOrder() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        UUID adminId = createUser(ADMIN_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));
        String orderId = createOrder(customerId, gamePackage);

        mockMvc.perform(get("/api/v1/admin/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId))
                .andExpect(jsonPath("$.data.history", hasSize(1)));
    }

    @Test
    void customerCannotReachTheAdminEndpoints() throws Exception {
        UUID customerId = createUser(USER_ROLE);

        mockMvc.perform(get("/api/v1/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/orders/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void everyOrderNumberIsUnique() throws Exception {
        UUID customerId = createUser(USER_ROLE);
        Game game = createGame(false);
        GamePackage gamePackage = createPackage(game, new BigDecimal("9.99"));

        String first = createOrder(customerId, gamePackage);
        String second = createOrder(customerId, gamePackage);

        assertThat(first).isNotEqualTo(second);
    }

    private String createOrder(UUID customerId, GamePackage gamePackage) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gamePackage.getId(), "Player-1", null)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private String createBody(UUID packageId, String playerId, String zoneId) {
        StringBuilder body = new StringBuilder("{\"packageId\":\"" + packageId + "\",\"playerId\":\"" + playerId + "\"");
        if (zoneId != null) {
            body.append(",\"zoneId\":\"").append(zoneId).append("\"");
        }
        return body.append("}").toString();
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
        return createUser(roleName, AccountStatus.ACTIVE);
    }

    private UUID createUser(String roleName, AccountStatus status) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User();
        user.setFullName("Test User");
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(status);
        userRepository.save(user);
        user.addRole(role);
        userRepository.save(user);
        return user.getId();
    }

    private Game createGame(boolean requiresZoneId) {
        Game game = new Game();
        game.setName("Test Game");
        game.setSlug("order-test-" + UUID.randomUUID().toString().substring(0, 8));
        game.setPlayerIdLabel("Player ID");
        game.setRequiresZoneId(requiresZoneId);
        game.setActive(true);
        return gameRepository.save(game);
    }

    private GamePackage createPackage(Game game, BigDecimal price) {
        GamePackage gamePackage = new GamePackage();
        gamePackage.setGameId(game.getId());
        gamePackage.setName("60 Diamonds");
        gamePackage.setSku("SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        gamePackage.setDiamondAmount(60);
        gamePackage.setPrice(price);
        gamePackage.setCurrency("USD");
        gamePackage.setActive(true);
        return gamePackageRepository.save(gamePackage);
    }
}
