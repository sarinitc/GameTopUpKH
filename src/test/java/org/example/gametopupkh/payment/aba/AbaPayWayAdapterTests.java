package org.example.gametopupkh.payment.aba;

import java.math.BigDecimal;

import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.example.gametopupkh.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AbaPayWayAdapterTests {

    private MockRestServiceServer server;
    private AbaPayWayGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = gatewayWithApiKey("test-key");
    }

    @Test
    void createPreservesConfiguredUrlHeaderAndLegacyJsonBody() {
        server.expect(requestTo("https://payments.example.test/custom/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-KEY", "test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "orderId":"order-1", "amount":10.50, "currency":"USD",
                          "customerReference":"player-1", "callbackUrl":"https://shop.example.test/callback",
                          "description":"Test order", "merchantId":"merchant-1"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "transaction_id":"provider-1", "status":"success",
                          "amount":999, "currency":"KHR", "redirect_url":"https://pay.example.test/1",
                          "provider_metadata":{"unknown_field":true}
                        }
                        """, MediaType.APPLICATION_JSON));

        PaymentIntent intent = gateway.createPayment(request());

        assertThat(intent.gateway()).isEqualTo("aba");
        assertThat(intent.gatewayReference()).isEqualTo("provider-1");
        assertThat(intent.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(intent.amount()).isEqualByComparingTo("10.50");
        assertThat(intent.currency()).isEqualTo("USD");
        assertThat(intent.redirectUrl()).isEqualTo("https://pay.example.test/1");
        assertThat(intent.createdAt()).isNotNull();
        server.verify();
    }

    @Test
    void statusUsesConfiguredGetPathAndFallsBackThroughNullAliases() {
        expectStatus("""
                {
                  "transaction_id":null, "reference":"provider-2", "id":"ignored",
                  "status":null, "state":"paid", "amount":12.75, "currency":"KHR",
                  "redirect_url":null, "payment_url":"https://pay.example.test/2", "qr_url":"ignored",
                  "extra_provider_field":"retained"
                }
                """);

        PaymentIntent intent = gateway.checkStatus("provider-2");

        assertThat(intent.gatewayReference()).isEqualTo("provider-2");
        assertThat(intent.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(intent.amount()).isEqualByComparingTo("12.75");
        assertThat(intent.currency()).isEqualTo("KHR");
        assertThat(intent.redirectUrl()).isEqualTo("https://pay.example.test/2");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "PAID, COMPLETED", "COMPLETED, COMPLETED", "success, COMPLETED",
            "FAILED, FAILED", "ERROR, FAILED", "EXPIRED, EXPIRED",
            "CANCELLED, CANCELLED", "VOIDED, CANCELLED", "new-provider-state, PENDING"
    })
    void statusPreservesProviderStateMapping(String providerStatus, PaymentStatus expected) {
        expectStatus("{\"id\":\"provider-2\",\"state\":\"" + providerStatus + "\"}");

        PaymentIntent intent = gateway.checkStatus("provider-2");

        assertThat(intent.status()).isEqualTo(expected);
        assertThat(intent.amount()).isNull();
        assertThat(intent.currency()).isNull();
        server.verify();
    }

    @Test
    void statusAllowsMissingStatusAndUsesLastReferenceAndUrlAliases() {
        expectStatus("{\"id\":\"provider-2\",\"qr_url\":\"https://pay.example.test/qr\"}");

        PaymentIntent intent = gateway.checkStatus("provider-2");

        assertThat(intent.gatewayReference()).isEqualTo("provider-2");
        assertThat(intent.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(intent.redirectUrl()).isEqualTo("https://pay.example.test/qr");
        server.verify();
    }

    @Test
    void missingReferencePreservesReadFailureAndItsCause() {
        expectStatus("{\"status\":\"PAID\"}");

        assertThatThrownBy(() -> gateway.checkStatus("provider-2"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessage("Failed to read AbaPayWay payment: AbaPayWay response did not contain a transaction reference")
                .hasCauseInstanceOf(PaymentGatewayException.class);
        server.verify();
    }

    @Test
    void emptyCreateResponsePreservesCreateFailureAndItsCause() {
        server.expect(requestTo("https://payments.example.test/custom/create"))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.createPayment(request()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessage("Failed to create AbaPayWay payment: Empty response from AbaPayWay")
                .hasCauseInstanceOf(PaymentGatewayException.class);
        server.verify();
    }

    @Test
    void transportFailureIsWrappedWithGatewayContext() {
        server.expect(requestTo("https://payments.example.test/custom/check/provider-2"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> gateway.checkStatus("provider-2"))
                .isInstanceOfSatisfying(PaymentGatewayException.class,
                        exception -> assertThat(exception.getGateway()).isEqualTo("aba"))
                .hasMessageStartingWith("Failed to read AbaPayWay payment:");
        server.verify();
    }

    @Test
    void blankApiKeyFailsBeforeSendingEitherRequest() {
        gateway = gatewayWithApiKey(" ");

        assertThatThrownBy(() -> gateway.createPayment(request()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessage("payment.aba.api-key is not configured")
                .hasNoCause();
        assertThatThrownBy(() -> gateway.checkStatus("provider-2"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessage("payment.aba.api-key is not configured")
                .hasNoCause();
        server.verify();
    }

    private AbaPayWayGateway gatewayWithApiKey(String apiKey) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AbaPayWayProperties properties = new AbaPayWayProperties(
                "https://payments.example.test", apiKey, "/custom/create",
                "/custom/check/{reference}", "merchant-1");
        return new AbaPayWayGateway(new AbaPayWayClient(builder, properties), new AbaPayWayPaymentMapper());
    }

    private void expectStatus(String response) {
        server.expect(requestTo("https://payments.example.test/custom/check/provider-2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-KEY", "test-key"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private PaymentRequest request() {
        return new PaymentRequest("order-1", new BigDecimal("10.50"), "USD", "player-1",
                "https://shop.example.test/callback", "Test order");
    }
}
