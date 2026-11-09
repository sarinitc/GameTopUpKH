package org.example.gametopupkh.player.integration.volsever;

import java.io.IOException;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.player.dto.PlayerVerificationStatus;
import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VolseverPlayerLookupClientTests {

    private static final String BASE = "https://gate.volsever.com";
    private static final String KEY = "test-only-volsever-key";
    private static final String FREE_FIRE = "/proxy/api/game/free-fire-global";
    private static final String MOBILE_LEGENDS = "/proxy/api/game/mobile-legends";

    private MockRestServiceServer server;
    private VolseverPlayerLookupClient client;

    @BeforeEach
    void setUp() {
        VolseverProperties properties = new VolseverProperties();
        properties.setFreeFirePath(FREE_FIRE);
        properties.setMobileLegendsPath(MOBILE_LEGENDS);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE)
                .defaultHeader("X-API-Key", KEY);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new VolseverPlayerLookupClient(builder.build(), properties);
    }

    @Test
    void freeFireReturnsDocumentedNicknameAndRegion() {
        server.expect(requestTo(BASE + FREE_FIRE + "?id=686631178"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", KEY))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess("""
                        {"status":true,"code":200,"message":"ID Found","data":{
                          "game":"Free Fire Global Region","username":"pradhum5727F",
                          "user_id":"686631178","zone":null,"region":"IND","level":58,
                          "last_login":"1597176943","credit_score":0}}
                        """, MediaType.APPLICATION_JSON));

        PlayerVerifyResponse response = client.lookup("free-fire",
                new PlayerVerifyRequest("686631178", null));

        assertThat(response.status()).isEqualTo(PlayerVerificationStatus.VERIFIED);
        assertThat(response.playerId()).isEqualTo("686631178");
        assertThat(response.zoneId()).isNull();
        assertThat(response.nickname()).isEqualTo("pradhum5727F");
        assertThat(response.region()).isEqualTo("IND");
        assertThat(response.provider()).isEqualTo("VOLSEVER");
        assertThat(response.toString()).doesNotContain(KEY);
        server.verify();
    }

    @Test
    void mobileLegendsSendsAndEchoesZone() {
        server.expect(requestTo(BASE + MOBILE_LEGENDS + "?id=79132815&zone=2153"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", KEY))
                .andRespond(withSuccess("""
                        {"status":true,"code":200,"message":"ID Found","data":{
                          "username":"Bagus","user_id":"79132815","zone":"2153",
                          "game":"Check Region MLBB (Fast, Work)","region":"ID"}}
                        """, MediaType.APPLICATION_JSON));

        PlayerVerifyResponse response = client.lookup("mobile-legends",
                new PlayerVerifyRequest("79132815", "2153"));

        assertThat(response.status()).isEqualTo(PlayerVerificationStatus.VERIFIED);
        assertThat(response.zoneId()).isEqualTo("2153");
        assertThat(response.nickname()).isEqualTo("Bagus");
        assertThat(response.region()).isEqualTo("ID");
        server.verify();
    }

    @Test
    void notFoundIsANormalInvalidResultWithoutAccountFacts() {
        server.expect(requestTo(BASE + FREE_FIRE + "?id=404404404"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":false,\"code\":404,\"message\":\"ID not found\"}"));

        PlayerVerifyResponse response = client.lookup("free-fire",
                new PlayerVerifyRequest("404404404", null));

        assertThat(response.status()).isEqualTo(PlayerVerificationStatus.INVALID);
        assertThat(response.nickname()).isNull();
        assertThat(response.region()).isNull();
        assertThat(response.provider()).isEqualTo("VOLSEVER");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402, 403, 429, 500, 503})
    void providerFailuresAreMappedAndNeverRetried(int status) {
        server.expect(requestTo(BASE + FREE_FIRE + "?id=686631178"))
                .andRespond(withStatus(HttpStatus.valueOf(status))
                        .header("Retry-After", "30")
                        .body("provider echoed " + KEY));

        assertThatThrownBy(() -> client.lookup("free-fire", new PlayerVerifyRequest("686631178", null)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(switch (status) {
                        case 400 -> "VOLSEVER_INVALID_REQUEST";
                        case 401, 403 -> "VOLSEVER_AUTH_FAILED";
                        case 402 -> "VOLSEVER_INSUFFICIENT_BALANCE";
                        case 429 -> "VOLSEVER_RATE_LIMITED";
                        default -> "VOLSEVER_UPSTREAM_ERROR";
                    });
                    assertThat(error.getMessage()).doesNotContain(KEY);
                    assertThat(error.getCause()).isNull();
                });
        server.verify();
    }

    @Test
    void transportFailureDropsSensitiveDetails() {
        server.expect(requestTo(BASE + FREE_FIRE + "?id=686631178"))
                .andRespond(withException(new IOException("failed URL?api_key=" + KEY)));

        assertThatThrownBy(() -> client.lookup("free-fire", new PlayerVerifyRequest("686631178", null)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getCode()).isEqualTo("VOLSEVER_UNAVAILABLE");
                    assertThat(error.getMessage()).doesNotContain(KEY);
                    assertThat(error.getCause()).isNull();
                });
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not-json",
            "null",
            "[]",
            "{}",
            "{\"status\":false,\"code\":200,\"data\":{}}",
            "{\"status\":true,\"code\":201,\"data\":{}}",
            "{\"status\":true,\"code\":200,\"data\":{}}",
            "{\"status\":true,\"code\":200,\"data\":{\"username\":\"Name\",\"user_id\":\"999\"}}",
            "{\"status\":true,\"code\":200,\"data\":{\"username\":\"Name\",\"user_id\":\"686631178\",\"zone\":\"9\"}}",
            "{\"status\":true,\"code\":200,\"data\":{\"username\":\"Name\",\"user_id\":\"686631178\",\"region\":42}}"
    })
    void malformedOrMismatchedResponsesFailClosed(String body) {
        server.expect(requestTo(BASE + FREE_FIRE + "?id=686631178"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.lookup("free-fire", new PlayerVerifyRequest("686631178", null)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(error.getCode()).isEqualTo("VOLSEVER_INVALID_RESPONSE");
                    assertThat(error.getCause()).isNull();
                });
        server.verify();
    }

    @Test
    void mobileLegendsZoneMismatchFailsClosed() {
        server.expect(requestTo(BASE + MOBILE_LEGENDS + "?id=79132815&zone=2153"))
                .andRespond(withSuccess("""
                        {"status":true,"code":200,"data":{"username":"Name",
                        "user_id":"79132815","zone":"9999","region":"ID"}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.lookup("mobile-legends",
                new PlayerVerifyRequest("79132815", "2153")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("VOLSEVER_INVALID_RESPONSE"));
        server.verify();
    }

    @Test
    void oversizedResponseIsRejected() {
        server.expect(requestTo(BASE + FREE_FIRE + "?id=686631178"))
                .andRespond(withSuccess(" ".repeat(65_537), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.lookup("free-fire", new PlayerVerifyRequest("686631178", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("VOLSEVER_INVALID_RESPONSE"));
        server.verify();
    }

    @Test
    void unsupportedGamesNeverCallTheProvider() {
        assertThat(client.supports("free-fire")).isTrue();
        assertThat(client.supports("mobile-legends")).isTrue();
        assertThat(client.supports("other-game")).isFalse();

        PlayerVerifyResponse response = client.lookup("other-game", new PlayerVerifyRequest("123", null));
        assertThat(response.status()).isEqualTo(PlayerVerificationStatus.UNAVAILABLE);
        assertThat(response.nickname()).isNull();
        server.verify();
    }
}
