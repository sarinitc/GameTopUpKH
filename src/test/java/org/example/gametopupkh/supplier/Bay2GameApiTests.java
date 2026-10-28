package org.example.gametopupkh.supplier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.supplier.integration.bay2game.Bay2GameClient;
import org.example.gametopupkh.supplier.integration.bay2game.Bay2GameResponses;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class Bay2GameApiTests {
    private static final String BASE = "/api/v1/admin/suppliers/bay2game";
    private static final Instant FETCHED = Instant.parse("2026-10-27T00:00:00Z");

    @Nested
    @SpringBootTest(properties = {
            "spring.profiles.active=test",
            "spring.datasource.url=jdbc:h2:mem:bay2game-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa", "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
            "app.jwt.secret=bay2game-api-test-secret-at-least-thirty-two-bytes",
            "payment.gateway=mock", "supplier.client=mock", "supplier.mock.fail=false",
            "fulfillment.worker.enabled=false", "payment.reconciliation.enabled=false"
    })
    @AutoConfigureMockMvc
    class AvailableClient {
        @Autowired private MockMvc mvc;
        @MockitoBean private Bay2GameClient client;

        @Test
        void adminGetsAllowlistedProfileWithNoStoreHeader() throws Exception {
            when(client.profile()).thenReturn(new Bay2GameResponses.Profile(12, "merchant",
                    new BigDecimal("120.25"), "ACTIVE", "RESELLER", 7,
                    new BigDecimal("20.15"), FETCHED));

            admin(get(BASE + "/profile")).andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.id").value(12))
                    .andExpect(jsonPath("$.data.username").value("merchant"))
                    .andExpect(jsonPath("$.data.balance").value(120.25))
                    .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.role").value("RESELLER"))
                    .andExpect(jsonPath("$.data.totalOrders").value(7))
                    .andExpect(jsonPath("$.data.totalSpent").value(20.15))
                    .andExpect(jsonPath("$.data.fetchedAt").value("2026-10-27T00:00:00Z"))
                    .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                    .andExpect(jsonPath("$.data.password").doesNotExist())
                    .andExpect(jsonPath("$.data.email").doesNotExist());
        }

        @Test
        void adminGetsBalanceWithoutInventingCurrency() throws Exception {
            when(client.balance()).thenReturn(new Bay2GameResponses.Balance(new BigDecimal("120.25"), null, FETCHED));
            admin(get(BASE + "/balance")).andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.data.amount").value(120.25))
                    .andExpect(jsonPath("$.data.currency").isEmpty())
                    .andExpect(jsonPath("$.data.fetchedAt").value("2026-10-27T00:00:00Z"));
        }

        @Test
        void adminGetsCategoriesAndRequiredGameFields() throws Exception {
            when(client.categories()).thenReturn(new Bay2GameResponses.Categories(1,
                    List.of(new Bay2GameResponses.Category("freefire_sgmy", "Free Fire", "Game credits",
                            "https://example.invalid/freefire.png", List.of("userid"))), FETCHED));
            admin(get(BASE + "/categories")).andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.categories", hasSize(1)))
                    .andExpect(jsonPath("$.data.categories[0].gameCode").value("freefire_sgmy"))
                    .andExpect(jsonPath("$.data.categories[0].name").value("Free Fire"))
                    .andExpect(jsonPath("$.data.categories[0].description").value("Game credits"))
                    .andExpect(jsonPath("$.data.categories[0].imageUrl").value("https://example.invalid/freefire.png"))
                    .andExpect(jsonPath("$.data.categories[0].gameFields[0]").value("userid"))
                    .andExpect(jsonPath("$.data.fetchedAt").value("2026-10-27T00:00:00Z"));
        }

        @Test
        void adminGetsProductsForRequestedGameWithDecimalPrices() throws Exception {
            when(client.products("freefire_sgmy")).thenReturn(new Bay2GameResponses.Products(
                    new Bay2GameResponses.Game("freefire_sgmy", "Free Fire", "Game credits", "https://example.invalid/game.png"),
                    List.of(new Bay2GameResponses.Product(52, "ff-100", "100 diamonds", new BigDecimal("0.97"), "ACTIVE")),
                    "USD", FETCHED));
            admin(get(BASE + "/products").param("gameCode", "freefire_sgmy"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.data.game.gameCode").value("freefire_sgmy"))
                    .andExpect(jsonPath("$.data.game.name").value("Free Fire"))
                    .andExpect(jsonPath("$.data.game.description").value("Game credits"))
                    .andExpect(jsonPath("$.data.game.imageUrl").value("https://example.invalid/game.png"))
                    .andExpect(jsonPath("$.data.products", hasSize(1)))
                    .andExpect(jsonPath("$.data.products[0].id").value(52))
                    .andExpect(jsonPath("$.data.products[0].productCode").value("ff-100"))
                    .andExpect(jsonPath("$.data.products[0].name").value("100 diamonds"))
                    .andExpect(jsonPath("$.data.products[0].sellPrice").value(0.97))
                    .andExpect(jsonPath("$.data.products[0].status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.currency").value("USD"))
                    .andExpect(jsonPath("$.data.fetchedAt").value("2026-10-27T00:00:00Z"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"USER", "SUPPORT", "RESELLER", "ANONYMOUS"})
        void eachReadRouteRequiresAdminBeforeInvokingProvider(String role) throws Exception {
            for (String route : List.of("profile", "balance", "categories", "products")) {
                MockHttpServletRequestBuilder request = get(BASE + "/" + route).param("gameCode", "freefire_sgmy");
                if (!role.equals("ANONYMOUS")) request.with(user("restricted").roles(role));
                mvc.perform(request).andExpect(status().is(role.equals("ANONYMOUS") ? 401 : 403))
                        .andExpect(jsonPath("$.success").value(false));
            }
            verifyNoInteractions(client);
        }

        @Test
        void providerFailureKeepsStructuredSanitizedErrorEnvelope() throws Exception {
            when(client.profile()).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY,
                    "BAY2GAME_UNAVAILABLE", "Bay2Game is temporarily unavailable"));
            admin(get(BASE + "/profile")).andExpect(status().isBadGateway())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("BAY2GAME_UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("Bay2Game is temporarily unavailable"))
                    .andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(content().string(not(containsString("api_key"))))
                    .andExpect(content().string(not(containsString("api.bay2game"))));
        }

        @Test
        void adminMutationVerbsAreNotMapped() throws Exception {
            for (String route : List.of("profile", "balance", "categories", "products")) {
                for (MockHttpServletRequestBuilder request : List.of(post(BASE + "/" + route),
                        put(BASE + "/" + route), patch(BASE + "/" + route), delete(BASE + "/" + route))) {
                    admin(request).andExpect(status().isMethodNotAllowed());
                }
            }
            verifyNoInteractions(client);
        }

        private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
            return mvc.perform(request.with(user("admin").roles("ADMIN")));
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.profiles.active=test",
            "spring.datasource.url=jdbc:h2:mem:bay2game-disabled;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa", "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
            "app.jwt.secret=bay2game-disabled-test-secret-at-least-thirty-two-bytes",
            "payment.gateway=mock", "supplier.client=mock", "supplier.mock.fail=false",
            "fulfillment.worker.enabled=false", "payment.reconciliation.enabled=false"
    })
    @AutoConfigureMockMvc
    class DefaultDisabled {
        @Autowired private MockMvc mvc;

        @Test
        void allReadsRemainAvailableButReturnServiceUnavailableByDefault() throws Exception {
            for (String route : List.of("profile", "balance", "categories", "products")) {
                mvc.perform(get(BASE + "/" + route).param("gameCode", "freefire_sgmy")
                                .with(user("admin").roles("ADMIN")))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                        .andExpect(jsonPath("$.success").value(false))
                        .andExpect(jsonPath("$.code").value("BAY2GAME_DISABLED"))
                        .andExpect(jsonPath("$.data").isEmpty())
                        .andExpect(content().string(not(containsString("api_key"))));
            }
        }
    }
}
