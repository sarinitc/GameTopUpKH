package org.example.gametopupkh.payment.bakong;

import java.math.BigDecimal;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckAccountResponse;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BakongApiClientTests {

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private BakongProperties properties;
    private BakongApiClient client;

    @BeforeEach
    void setUp() {
        properties = new BakongProperties(
                "sandbox",
                true,
                "https://example.com",
                "valid-bearer-token",
                "merchant@bank",
                "GameTopUp-KH",
                "Phnom Penh",
                10,
                java.time.Duration.ofSeconds(2),
                java.time.Duration.ofSeconds(2),
                2
        );
        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new BakongApiClient(restClientBuilder, properties);
    }

    @Test
    @DisplayName("Successfully checks transaction by MD5 and deserializes response")
    void successfulCheckTransactionByMd5() {
        String md5 = "0123456789abcdef0123456789abcdef";
        String responseJson = """
                {
                    "responseCode": 0,
                    "responseMessage": "Success",
                    "data": {
                        "hash": "b5d8c1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0",
                        "fromAccountId": "customer@devb",
                        "toAccountId": "merchant@bank",
                        "currency": "USD",
                        "amount": 4.25,
                        "description": "Order 123",
                        "createdDateMs": 1700000000000,
                        "acknowledgedDateMs": 1700000001000,
                        "externalRef": "EXT-12345"
                    }
                }
                """;

        mockServer.expect(requestTo("https://example.com/v1/check_transaction_by_md5"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer valid-bearer-token"))
                .andExpect(jsonPath("$.md5").value(md5))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BakongCheckTransactionResponse response = client.checkTransaction(md5);

        mockServer.verify();
        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.responseCode()).isEqualTo(0);
        assertThat(response.data()).isNotNull();
        assertThat(response.data().hash()).isEqualTo("b5d8c1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0");
        assertThat(response.data().amount()).isEqualByComparingTo(new BigDecimal("4.25"));
        assertThat(response.data().currency()).isEqualTo("USD");
        assertThat(response.data().toAccountId()).isEqualTo("merchant@bank");
    }

    @Test
    @DisplayName("Verifies a Bakong account via /v1/check_bakong_account")
    void verifiesBakongAccount() {
        String responseJson = """
                {
                    "responseCode": 0,
                    "responseMessage": "Success",
                    "data": { "accountId": "merchant@bank" }
                }
                """;

        mockServer.expect(requestTo("https://example.com/v1/check_bakong_account"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer valid-bearer-token"))
                .andExpect(jsonPath("$.accountId").value("merchant@bank"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BakongCheckAccountResponse response = client.checkAccount("merchant@bank");

        mockServer.verify();
        assertThat(response).isNotNull();
        assertThat(response.isVerified()).isTrue();
        assertThat(response.responseCode()).isZero();
        assertThat(response.responseMessage()).isEqualTo("Success");
    }

    @Test
    @DisplayName("Reports an unverified Bakong account without throwing")
    void reportsUnverifiedBakongAccount() {
        String responseJson = """
                {
                    "responseCode": 11,
                    "responseMessage": "Account not found",
                    "data": null
                }
                """;

        mockServer.expect(requestTo("https://example.com/v1/check_bakong_account"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BakongCheckAccountResponse response = client.checkAccount("missing@bank");

        mockServer.verify();
        assertThat(response.isVerified()).isFalse();
        assertThat(response.responseCode()).isEqualTo(11);
    }

    @Test
    @DisplayName("Rejects a blank Bakong account ID before calling the provider")
    void rejectsBlankAccountId() {
        assertThatThrownBy(() -> client.checkAccount("   "))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Handles transaction not found / pending response without throwing exception")
    void handlesTransactionNotFound() {
        String md5 = "unpaid-md5-hash";
        String responseJson = """
                {
                    "responseCode": 1,
                    "responseMessage": "Transaction not found",
                    "data": null
                }
                """;

        mockServer.expect(requestTo("https://example.com/v1/check_transaction_by_md5"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BakongCheckTransactionResponse response = client.checkTransaction(md5);

        mockServer.verify();
        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.responseCode()).isEqualTo(1);
        assertThat(response.data()).isNull();
    }

    @Test
    @DisplayName("Throws PaymentGatewayException on 401 Unauthorized without leaking token")
    void handles401Unauthorized() {
        String md5 = "test-md5";

        mockServer.expect(requestTo("https://example.com/v1/check_transaction_by_md5"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.checkTransaction(md5))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("authentication failed");
    }

    @Test
    @DisplayName("Throws ApiException on 429 Too Many Requests")
    void handles429RateLimit() {
        String md5 = "test-md5";

        mockServer.expect(requestTo("https://example.com/v1/check_transaction_by_md5"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.checkTransaction(md5))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("Retries transient 5xx server errors and fails after the configured attempts")
    void handles500ServerError() {
        String md5 = "test-md5";

        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            mockServer.expect(requestTo("https://example.com/v1/check_transaction_by_md5"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        }

        assertThatThrownBy(() -> client.checkTransaction(md5))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("failed after 3 attempt(s)")
                .hasMessageContaining("server error");
        mockServer.verify();
    }

    @Test
    @DisplayName("Retries a transient 5xx error and succeeds on a later attempt")
    void retriesTransientErrorThenSucceeds() {
        String md5 = "0123456789abcdef0123456789abcdef";
        String responseJson = """
                {
                    "responseCode": 0,
                    "responseMessage": "Success",
                    "data": {
                        "hash": "tx-hash",
                        "toAccountId": "merchant@bank",
                        "currency": "USD",
                        "amount": 4.25
                    }
                }
                """;

        mockServer.expect(requestTo("https://example.com/v1/check_transaction_by_md5"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        mockServer.expect(requestTo("https://example.com/v1/check_transaction_by_md5"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BakongCheckTransactionResponse response = client.checkTransaction(md5);

        mockServer.verify();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.data().toAccountId()).isEqualTo("merchant@bank");
    }

    @Test
    @DisplayName("Blocks live payment checking when live payments are disabled in production")
    void blocksWhenDisabledInProduction() {
        BakongProperties disabledProdProps = new BakongProperties(
                "production",
                false, // disabled
                "https://example.com",
                "token",
                "acc@bank",
                "GameTopUp-KH",
                "Phnom Penh",
                10,
                java.time.Duration.ofSeconds(2),
                java.time.Duration.ofSeconds(2),
                2
        );
        BakongApiClient prodClient = new BakongApiClient(restClientBuilder, disabledProdProps);

        assertThatThrownBy(() -> prodClient.checkTransaction("any-md5"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("disabled by configuration");
    }
}
