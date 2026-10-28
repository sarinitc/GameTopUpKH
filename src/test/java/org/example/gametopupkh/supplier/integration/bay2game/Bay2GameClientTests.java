package org.example.gametopupkh.supplier.integration.bay2game;

import java.io.IOException;
import java.math.BigDecimal;

import org.example.gametopupkh.common.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class Bay2GameClientTests {
    private static final String BASE = "https://api.bay2game.xyz/api";
    private static final String KEY = "test-only-key+&=?";
    private static final String AUTH = "api_key=test-only-key%2B%26%3D%3F";
    private static final String PROFILE = """
            {"status":"SUCCESS","user":{"id":1,"telegram_id":8821434690,"username":"DemoUser",
            "balance":98.901234567890123456,"status":"active","role":"reseller","total_orders":9,
            "total_spent":0.65,"created_at":"2026-06-18 13:42:58","updated_at":"2026-06-20 10:30:15",
            "api_key":"must-never-be-returned"}}
            """;
    private MockRestServiceServer server;
    private Bay2GameClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new Bay2GameClient(builder.build(), KEY);
    }

    @Test
    void profileUsesDocumentedHostAndEncodedQueryAuthenticationAndPreservesMoney() {
        server.expect(requestTo(BASE + "/profile?" + AUTH)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", "application/json"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(PROFILE, MediaType.APPLICATION_JSON));
        var profile = client.profile();
        assertThat(profile.id()).isEqualTo(1);
        assertThat(profile.username()).isEqualTo("DemoUser");
        assertThat(profile.balance()).isEqualByComparingTo("98.901234567890123456");
        assertThat(profile.totalSpent()).isEqualByComparingTo("0.65");
        assertThat(profile.totalOrders()).isEqualTo(9);
        assertThat(profile.accountStatus()).isEqualTo("active");
        assertThat(profile.fetchedAt()).isNotNull();
        assertThat(profile.toString()).doesNotContain("must-never-be-returned", "telegram_id", KEY);
        server.verify();
    }

    @Test
    void balanceReadsProfileAndDoesNotInventUndocumentedCurrency() {
        server.expect(requestTo(BASE + "/profile?" + AUTH))
                .andRespond(withSuccess(PROFILE, MediaType.APPLICATION_JSON));
        var balance = client.balance();
        assertThat(balance.amount()).isEqualByComparingTo(new BigDecimal("98.901234567890123456"));
        assertThat(balance.currency()).isNull();
        assertThat(balance.fetchedAt()).isNotNull();
        server.verify();
    }

    @Test
    void categoriesIncludeProviderGameCodeAndRequiredAccountFields() {
        server.expect(requestTo(BASE + "/categories?" + AUTH)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                    {"status":"SUCCESS","total":1,"categories":[{"game_code":"freefire_sgmy",
                    "name":"Free Fire","description":"Available for SG/MY/PH/KH/EUROPE/BD",
                    "image_url":"https://bay2game.xyz/images/freefire.png","game_fields":["userid"]}]}
                    """, MediaType.APPLICATION_JSON));
        var result = client.categories();
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.categories()).hasSize(1);
        assertThat(result.categories().get(0).gameCode()).isEqualTo("freefire_sgmy");
        assertThat(result.categories().get(0).gameFields()).containsExactly("userid");
        server.verify();
    }

    @Test
    void productsRequireExactGameAndPreserveDocumentedUsdPrice() {
        server.expect(requestTo(BASE + "/products?" + AUTH + "&game_code=mlbb_exclusive"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("""
                    {"status":"SUCCESS","game":{"game_code":"mlbb_exclusive","name":"Mobile Legends Bang Bang",
                    "description":"Top-up and diamonds","image_url":"https://bay2game.xyz/images/mlbb.png"},
                    "products":[{"id":1,"product_code":"MLBB_100","name":"100 Diamonds","sell_price":1.50,"status":"active"}]}
                    """, MediaType.APPLICATION_JSON));
        var result = client.products("mlbb_exclusive");
        assertThat(result.game().gameCode()).isEqualTo("mlbb_exclusive");
        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).productCode()).isEqualTo("MLBB_100");
        assertThat(result.products().get(0).sellPrice()).isEqualByComparingTo("1.50");
        assertThat(result.currency()).isEqualTo("USD");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "../create_order", "freefire_sgmy&reference=order", "free fire", "https://evil.test"})
    void invalidGameCodeFailsBeforeAnyHttpRequest(String gameCode) {
        assertError(() -> client.products(gameCode), HttpStatus.BAD_REQUEST, "INVALID_GAME_CODE");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 401, 403, 429, 500, 503})
    void httpFailuresAreSanitizedAndNeverRetried(int status) {
        server.expect(requestTo(BASE + "/profile?" + AUTH))
                .andRespond(withStatus(HttpStatus.valueOf(status))
                        .header("Location", "https://untrusted.test/?api_key=" + KEY)
                        .body("provider echoed " + KEY));
        String code = status == 429 ? "BAY2GAME_RATE_LIMITED"
                : status == 401 || status == 403 ? "BAY2GAME_AUTH_FAILED" : "BAY2GAME_UPSTREAM_ERROR";
        assertError(client::profile, status == 429 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY, code);
        server.verify();
    }

    @Test
    void transportFailureDropsSensitiveUrlAndCause() {
        server.expect(requestTo(BASE + "/profile?" + AUTH))
                .andRespond(withException(new IOException("failed URL?api_key=" + KEY)));
        assertError(client::profile, HttpStatus.SERVICE_UNAVAILABLE, "BAY2GAME_UNAVAILABLE");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "null", "[]", "{}", "{\"status\":\"SUCCESS\"}",
            "{\"status\":\"ERROR\",\"message\":\"test-only-key+&=?\"}",
            "{\"status\":\"SUCCESS\",\"user\":{\"id\":1,\"balance\":null}}"})
    void malformedAndProviderErrorResponsesNeverBecomeSuccessfulEmptyData(String body) {
        server.expect(requestTo(BASE + "/profile?" + AUTH)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::profile).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(error.getMessage()).doesNotContain(KEY, body.isEmpty() ? "not-present" : body);
            assertThat(error.getCause()).isNull();
        });
        server.verify();
    }

    @Test
    void oversizedResponseIsRejected() {
        server.expect(requestTo(BASE + "/profile?" + AUTH))
                .andRespond(withSuccess(" ".repeat(1_048_577), MediaType.APPLICATION_JSON));
        assertError(client::profile, HttpStatus.BAD_GATEWAY, "BAY2GAME_INVALID_RESPONSE");
        server.verify();
    }

    @Test
    void wrongGameInProductsResponseIsRejected() {
        server.expect(requestTo(BASE + "/products?" + AUTH + "&game_code=freefire_sgmy"))
                .andRespond(withSuccess("""
                    {"status":"SUCCESS","game":{"game_code":"other","name":"Wrong game"},"products":[]}
                    """, MediaType.APPLICATION_JSON));
        assertError(() -> client.products("freefire_sgmy"), HttpStatus.BAD_GATEWAY, "BAY2GAME_INVALID_RESPONSE");
        server.verify();
    }

    private void assertError(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(status);
            assertThat(error.getCode()).isEqualTo(code);
            assertThat(error.getMessage()).doesNotContain(KEY, "api_key", "untrusted.test");
            assertThat(error.getCause()).isNull();
        });
    }
}
