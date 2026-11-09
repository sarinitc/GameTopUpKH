package org.example.gametopupkh.supplier.integration.vsc;

import java.io.IOException;
import java.util.UUID;

import org.example.gametopupkh.supplier.integration.SupplierClient.SupplierException;
import org.example.gametopupkh.supplier.integration.SupplierTopupRequest;
import org.example.gametopupkh.supplier.integration.SupplierTopupResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

class VscSupplierClientTests {

    private static final String BASE = "https://api.vscgames.com";
    private static final String KEY = "test-only-merchant-key";
    private static final String PATH = BASE + "/freefire-merchant-topup";
    private static final String OK_BODY = """
            {"code":200,"message":"success","orderId":"VSC-ORDER-0042","status":"Completed","amount":1.5}
            """;

    private MockRestServiceServer server;
    private VscSupplierClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new VscSupplierClient(builder.build(), KEY, "GameTopUp-KH");
    }

    private static SupplierTopupRequest request(String playerId, String externalSku, int quantity) {
        return new SupplierTopupRequest(UUID.randomUUID(), "ORD-2026-0001", UUID.randomUUID(),
                "free-fire", "310 Diamonds", playerId, null, quantity, UUID.randomUUID(), externalSku);
    }

    @Test
    @DisplayName("Successful top-up requires code 200 AND status Completed; reference is the provider orderId")
    void successRequiresCode200AndCompleted() {
        server.expect(requestTo(PATH + "?productId=123456&playerID=987654321&Merchantname=GameTopUp-KH"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("merchantkey", KEY))
                .andExpect(header("Accept", "application/json"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        SupplierTopupResponse response = client.fulfill(request("987654321", "123456", 1));

        assertThat(response.success()).isTrue();
        assertThat(response.supplierReference()).isEqualTo("VSC-ORDER-0042");
        assertThat(response.message()).doesNotContain(KEY);
        server.verify();
    }

    @Test
    @DisplayName("Status comparison trims and is case-insensitive")
    void statusComparisonToleratesCaseAndSurroundingSpace() {
        server.expect(requestTo(PATH + "?productId=123456&playerID=987654321&Merchantname=GameTopUp-KH"))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"ok\",\"orderId\":\"VSC-9\",\"status\":\" completed \",\"amount\":1.5}",
                        MediaType.APPLICATION_JSON));

        SupplierTopupResponse response = client.fulfill(request("987654321", "123456", 1));

        assertThat(response.success()).isTrue();
        assertThat(response.supplierReference()).isEqualTo("VSC-9");
        server.verify();
    }

    @ParameterizedTest(name = "status={0} is never success")
    @ValueSource(strings = {"Pending", "Processing", "Failed", "completed-with-suffix", "COMPLETED!", ""})
    void nonCompletedStatusIsNeverSuccess(String status) {
        String body = "{\"code\":200,\"message\":\"x\",\"orderId\":\"VSC-1\",\"status\":\"" + status + "\",\"amount\":1.5}";
        server.expect(requestTo(PATH + "?productId=123456&playerID=987654321&Merchantname=GameTopUp-KH"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fulfill(request("987654321", "123456", 1)))
                .isInstanceOf(SupplierException.class)
                .hasMessageContaining("outcome requires review")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY))
                .hasNoCause();
        server.verify();
    }

    @Test
    @DisplayName("code 200 with a failing code such as 400 is never success")
    void failingCodeIsNeverSuccessEvenWithCompletedStatus() {
        server.expect(requestTo(PATH + "?productId=123456&playerID=987654321&Merchantname=GameTopUp-KH"))
                .andRespond(withSuccess(
                        "{\"code\":400,\"message\":\"insufficient balance\",\"orderId\":null,\"status\":\"Completed\",\"amount\":1.5}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fulfill(request("987654321", "123456", 1)))
                .isInstanceOf(SupplierException.class)
                .hasMessageContaining("code=400")
                .hasMessageContaining("outcome requires review")
                .hasNoCause();
        server.verify();
    }

    @Test
    @DisplayName("Success without a provider orderId is passed through blank for the framework to flag for review")
    void successWithoutOrderIdPassesBlankReference() {
        server.expect(requestTo(PATH + "?productId=123456&playerID=987654321&Merchantname=GameTopUp-KH"))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"ok\",\"status\":\"Completed\",\"amount\":1.5}",
                        MediaType.APPLICATION_JSON));

        SupplierTopupResponse response = client.fulfill(request("987654321", "123456", 1));

        assertThat(response.success()).isTrue();
        assertThat(response.supplierReference()).isNull();
    }

    @ParameterizedTest(name = "playerId={0} is rejected before any outbound call")
    @ValueSource(strings = {"12345abc", "12 345", "12-345", "+12345", "١٢٣٤٥", "１２３", ""})
    void nonNumericPlayerIdIsRejectedLocally(String playerId) {
        SupplierTopupResponse response = client.fulfill(request(playerId, "123456", 1));

        assertThat(response.success()).isFalse();
        assertThat(response.message()).contains("numeric");
        server.verify(); // any outbound request fails the test
    }

    @Test
    @DisplayName("Null or blank player ID is rejected locally")
    void blankPlayerIdIsRejectedLocally() {
        assertThat(client.fulfill(request(null, "123456", 1)).success()).isFalse();
        assertThat(client.fulfill(request("   ", "123456", 1)).success()).isFalse();
        server.verify();
    }

    @ParameterizedTest(name = "externalSku={0} is rejected before any outbound call")
    @ValueSource(strings = {"SKU-123", "ff_310", "12.5", ""})
    void nonNumericProductIdIsRejectedLocally(String externalSku) {
        SupplierTopupResponse response = client.fulfill(request("987654321", externalSku, 1));

        assertThat(response.success()).isFalse();
        assertThat(response.message()).contains("numeric");
        server.verify();
    }

    @Test
    @DisplayName("Quantity other than 1 is rejected locally before any outbound call")
    void nonSingleQuantityIsRejectedLocally() {
        SupplierTopupResponse response = client.fulfill(request("987654321", "123456", 2));

        assertThat(response.success()).isFalse();
        assertThat(response.message()).contains("quantity 1");
        server.verify();
    }

    @ParameterizedTest(name = "HTTP {0} fails closed and never echoes the key")
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 502, 503})
    void errorHttpStatusesFailClosed(int status) {
        server.expect(requestTo(PATH + "?productId=123456&playerID=987654321&Merchantname=GameTopUp-KH"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("provider error with " + KEY));

        assertThatThrownBy(() -> client.fulfill(request("987654321", "123456", 1)))
                .isInstanceOf(SupplierException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(KEY);
                    assertThat(e.getCause()).isNull();
                });
        server.verify();
    }

    @Test
    @DisplayName("Transport failure drops the credential-bearing URI and cause")
    void transportFailureDropsCauseAndKey() {
        server.expect(requestTo(PATH + "?productId=123456&playerID=987654321&Merchantname=GameTopUp-KH"))
                .andRespond(withException(new IOException("failed " + KEY + " at " + PATH)));

        assertThatThrownBy(() -> client.fulfill(request("987654321", "123456", 1)))
                .isInstanceOf(SupplierException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(KEY);
                    assertThat(e.getCause()).isNull();
                });
        server.verify();
    }

    @ParameterizedTest(name = "body={0} is an unusable response")
    @ValueSource(strings = {"", "not-json", "null", "[]", "{}", "{\"code\":\"200\"}", "{\"status\":\"Completed\"}",
            "{\"code\":200.5,\"status\":\"Completed\"}", "{\"code\":200,\"status\":\"Completed\",\"amount\":-1}",
            "{\"code\":200,\"status\":\"Completed\",\"amount\":\"1.50\"}", "{\"code\":200,\"status\":42}"})
    void unusableBodiesRaiseSupplierException(String body) {
        server.expect(requestTo(PATH + "?productId=123456&playerID=987654321&Merchantname=GameTopUp-KH"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fulfill(request("987654321", "123456", 1)))
                .isInstanceOf(SupplierException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY))
                .hasNoCause();
        server.verify();
    }

    @Test
    @DisplayName("Oversized response body is rejected")
    void oversizedBodyIsRejected() {
        String big = "{\"code\":200,\"status\":\"Completed\",\"orderId\":\""
                + "x".repeat(1_100_000) + "\"}";
        server.expect(requestTo(PATH + "?productId=123456&playerID=987654321&Merchantname=GameTopUp-KH"))
                .andRespond(withSuccess(big, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fulfill(request("987654321", "123456", 1)))
                .isInstanceOf(SupplierException.class)
                .hasNoCause();
        server.verify();
    }

    @Test
    @DisplayName("Merchant name with a space is URL-encoded in the query string")
    void merchantNameIsUrlEncoded() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer spacedServer = MockRestServiceServer.bindTo(builder).build();
        VscSupplierClient spaced = new VscSupplierClient(builder.build(), KEY, "Game Top Up");
        spacedServer.expect(requestTo(PATH + "?productId=123456&playerID=987654321&Merchantname=Game%20Top%20Up"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        SupplierTopupResponse response = spaced.fulfill(request("987654321", "123456", 1));

        assertThat(response.success()).isTrue();
        spacedServer.verify();
    }

    @Test
    @DisplayName("Supplier orderId is truncated downstream; order references are carried verbatim")
    void providerOrderIdIsCarriedAsSupplierReference() {
        server.expect(requestTo(PATH + "?productId=123456&playerID=987654321&Merchantname=GameTopUp-KH"))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"done\",\"orderId\":\"  VSC-ORDER-77  \",\"status\":\"Completed\",\"amount\":1.5}",
                        MediaType.APPLICATION_JSON));

        SupplierTopupResponse response = client.fulfill(request("987654321", "123456", 1));

        assertThat(response.success()).isTrue();
        assertThat(response.supplierReference()).isEqualTo("VSC-ORDER-77");
    }
}
