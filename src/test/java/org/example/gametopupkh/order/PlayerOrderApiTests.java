package org.example.gametopupkh.order;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.example.gametopupkh.catalog.game.entity.Game;
import org.example.gametopupkh.catalog.game.repository.GameRepository;
import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.catalog.packagecatalog.repository.GamePackageRepository;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:player-order;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "fulfillment.worker.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class PlayerOrderApiTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private GameRepository games;
    @Autowired private GamePackageRepository packages;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;

    private UUID customerId;

    @BeforeEach
    void createCustomer() {
        User customer = new User();
        customer.setFullName("Player validation customer");
        customer.setEmail("player-order-" + UUID.randomUUID() + "@example.test");
        customer.setPasswordHash("unused-authenticated-test-password");
        customerId = users.saveAndFlush(customer).getId();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "12 34", "+123", "１２３", "123456789012345678901"})
    void freeFireOrdersCannotBypassNumericPlayerValidation(String playerId) throws Exception {
        UUID packageId = createPackage("free-fire", false);
        long before = orders.count();

        createOrder(packageId, playerId, null).andExpect(status().isBadRequest());

        assertThat(orders.count()).isEqualTo(before);
    }

    @Test
    void mobileLegendsRequiresAZoneEvenWhenTheCatalogFlagIsFalse() throws Exception {
        UUID packageId = createPackage("mobile-legends", false);

        createOrder(packageId, "00123456", null).andExpect(status().isBadRequest());

        assertThat(orders.findAllByUser_IdOrderByCreatedAtDesc(customerId)).isEmpty();
    }

    @Test
    void mobileLegendsRejectsMalformedZones() throws Exception {
        UUID packageId = createPackage("mobile-legends", true);

        createOrder(packageId, "123456", "zone-1").andExpect(status().isBadRequest());

        assertThat(orders.findAllByUser_IdOrderByCreatedAtDesc(customerId)).isEmpty();
    }

    @Test
    void mobileLegendsPreservesNormalizedIdsDespiteAFalseCatalogZoneFlag() throws Exception {
        UUID packageId = createPackage("mobile-legends", false);

        createOrder(packageId, " 00123456 ", " 0012 ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.playerId").value("00123456"))
                .andExpect(jsonPath("$.data.zoneId").value("0012"))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"));

        assertThat(orders.findAllByUser_IdOrderByCreatedAtDesc(customerId)).singleElement()
                .satisfies(order -> {
                    assertThat(order.getPlayerId()).isEqualTo("00123456");
                    assertThat(order.getZoneId()).isEqualTo("0012");
                });
    }

    @Test
    void freeFireDropsTheZoneWhilePreservingLeadingZeroes() throws Exception {
        UUID packageId = createPackage("free-fire", false);

        createOrder(packageId, " 00012345 ", "discard-me")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.playerId").value("00012345"))
                .andExpect(jsonPath("$.data.zoneId").isEmpty());
    }

    @Test
    void otherGamesKeepTheirExistingIdentifierRules() throws Exception {
        UUID packageId = createPackage("another-game", false);

        createOrder(packageId, " Player-1 ", "discard-me")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.playerId").value("Player-1"))
                .andExpect(jsonPath("$.data.zoneId").isEmpty());
    }

    private ResultActions createOrder(UUID packageId, String playerId, String zoneId) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("packageId", packageId);
        request.put("playerId", playerId);
        if (zoneId != null) {
            request.put("zoneId", zoneId);
        }
        return mvc.perform(post("/api/v1/orders")
                .with(user(customerId.toString()).roles("USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)));
    }

    private UUID createPackage(String slug, boolean requiresZoneId) {
        Game game = new Game();
        game.setName("Player verification game");
        game.setSlug(slug);
        game.setRequiresZoneId(requiresZoneId);
        game = games.saveAndFlush(game);
        GamePackage gamePackage = new GamePackage();
        gamePackage.setGameId(game.getId());
        gamePackage.setName("60 Diamonds");
        gamePackage.setSku("PLAYER-" + UUID.randomUUID());
        gamePackage.setDiamondAmount(60);
        gamePackage.setPrice(new BigDecimal("4.25"));
        return packages.saveAndFlush(gamePackage).getId();
    }
}
