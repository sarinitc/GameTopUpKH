package org.example.gametopupkh.payment.aba;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.jayway.jsonpath.JsonPath;
import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AbaPayWayOfficialApiTests {

    private static final String BASE_URL = "https://checkout-sandbox.payway.com.kh";
    private static final String CALLBACK_URL = "https://shop.example.test/callback";

    private MockRestServiceServer server;
    private AbaPayWayGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AbaPayWayProperties properties = new AbaPayWayProperties(
                BASE_URL, "test-key",
                AbaPayWayProperties.OFFICIAL_CREATE_PATH,
                AbaPayWayProperties.OFFICIAL_STATUS_PATH,
                "merchant-1");
        gateway = new AbaPayWayGateway(new AbaPayWayClient(builder, properties),
                new AbaPayWayPaymentMapper());
    }

    @Test
    void createUsesOfficialGenerateQrRequestAndHash() {
        String encodedCallback = Base64.getEncoder()
                .encodeToString(CALLBACK_URL.getBytes(StandardCharsets.UTF_8));

        server.expect(requestTo(BASE_URL + AbaPayWayProperties.OFFICIAL_CREATE_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.req_time", matchesPattern("\\d{14}")))
                .andExpect(jsonPath("$.merchant_id", is("merchant-1")))
                .andExpect(jsonPath("$.tran_id", is("order-1")))
                .andExpect(jsonPath("$.amount", is("10.50")))
                .andExpect(jsonPath("$.currency", is("USD")))
                .andExpect(jsonPath("$.purchase_type", is("purchase")))
                .andExpect(jsonPath("$.payment_option", is("abapay_khqr")))
                .andExpect(jsonPath("$.callback_url", is(encodedCallback)))
                .andExpect(jsonPath("$.lifetime", is(30)))
                .andExpect(jsonPath("$.qr_image_template", is("template3_color")))
                .andExpect(request -> {
                    MockClientHttpRequest httpRequest = (MockClientHttpRequest) request;
                    String body = httpRequest.getBodyAsString(StandardCharsets.UTF_8);
                    String reqTime = JsonPath.read(body, "$.req_time");
                    String expectedPayload = reqTime + "merchant-1" + "order-1" + "10.50"
                            + "" + "" + "" + "" + ""
                            + "purchase" + "abapay_khqr" + encodedCallback
                            + "" + "USD" + "" + "" + ""
                            + "30" + "template3_color";
                    assertThat((String) JsonPath.read(body, "$.hash"))
                            .isEqualTo(AbaPayWayHashService.hmacSha512Base64("test-key", expectedPayload));
                })
                .andRespond(withSuccess("""
                        {
                          "status":{"code":"0","message":"Success"},
                          "amount":10.50, "currency":"USD",
                          "qrString":"00020101021229",
                          "qrImage":"data:image/png;base64,abc",
                          "abapay_deeplink":"abaapp://pay",
                          "expire_in":1772594519
                        }
                        """, MediaType.APPLICATION_JSON));

        PaymentIntent intent = gateway.createPayment(request());

        assertThat(intent.gatewayReference()).isEqualTo("order-1");
        assertThat(intent.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(intent.amount()).isEqualByComparingTo("10.50");
        assertThat(intent.currency()).isEqualTo("USD");
        assertThat(intent.qrString()).isEqualTo("00020101021229");
        assertThat(intent.qrImageUrl()).isEqualTo("data:image/png;base64,abc");
        assertThat(intent.redirectUrl()).isEqualTo("abaapp://pay");
        assertThat(intent.expiresAt()).isEqualTo(Instant.ofEpochSecond(1772594519));
        server.verify();
    }

    @Test
    void checkUsesOfficialPostRequestAndMapsNestedApprovedTransaction() {
        server.expect(requestTo(BASE_URL + AbaPayWayProperties.OFFICIAL_STATUS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.req_time", matchesPattern("\\d{14}")))
                .andExpect(jsonPath("$.merchant_id", is("merchant-1")))
                .andExpect(jsonPath("$.tran_id", is("provider-2")))
                .andExpect(request -> {
                    MockClientHttpRequest httpRequest = (MockClientHttpRequest) request;
                    String body = httpRequest.getBodyAsString(StandardCharsets.UTF_8);
                    String reqTime = JsonPath.read(body, "$.req_time");
                    String expectedPayload = reqTime + "merchant-1" + "provider-2";
                    assertThat((String) JsonPath.read(body, "$.hash"))
                            .isEqualTo(AbaPayWayHashService.hmacSha512Base64("test-key", expectedPayload));
                })
                .andRespond(withSuccess("""
                        {
                          "data":{
                            "payment_status_code":0,
                            "total_amount":12.75,
                            "payment_amount":12.75,
                            "payment_currency":"USD",
                            "payment_status":"APPROVED"
                          },
                          "status":{"code":"00","message":"Success!","tran_id":"provider-2"}
                        }
                        """, MediaType.APPLICATION_JSON));

        PaymentIntent intent = gateway.checkStatus("provider-2");

        assertThat(intent.gatewayReference()).isEqualTo("provider-2");
        assertThat(intent.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(intent.amount()).isEqualByComparingTo("12.75");
        assertThat(intent.currency()).isEqualTo("USD");
        server.verify();
    }

    @Test
    void checkMapsPendingNestedTransactionWithoutCurrency() {
        server.expect(requestTo(BASE_URL + AbaPayWayProperties.OFFICIAL_STATUS_PATH))
                .andRespond(withSuccess("""
                        {
                          "data":{
                            "payment_status_code":2,
                            "total_amount":1.00,
                            "payment_amount":0,
                            "payment_currency":"",
                            "payment_status":"PENDING"
                          },
                          "status":{"code":"00","message":"Success!","tran_id":"provider-2"}
                        }
                        """, MediaType.APPLICATION_JSON));

        PaymentIntent intent = gateway.checkStatus("provider-2");

        assertThat(intent.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(intent.amount()).isEqualByComparingTo("1.00");
        assertThat(intent.currency()).isNull();
        server.verify();
    }

    @Test
    void providerErrorStatusFailsTheLookup() {
        server.expect(requestTo(BASE_URL + AbaPayWayProperties.OFFICIAL_STATUS_PATH))
                .andRespond(withSuccess("""
                        {
                          "status":{"code":6,"message":"tran_id not found","tran_id":"provider-2"}
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.checkStatus("provider-2"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("AbaPayWay request failed (6): tran_id not found");
        server.verify();
    }

    @Test
    void createNormalizesRawBase64QrImageWithoutCorruptingQrString() {
        server.expect(requestTo(BASE_URL + AbaPayWayProperties.OFFICIAL_CREATE_PATH))
                .andRespond(withSuccess("""
                        {
                          "status":{"code":"00","message":"Success"},
                          "amount":10.50, "currency":"USD",
                          "qrString":"00020101021229370016bakong@aba520458125303840540410.505802KH5912GameTopUp KH6010Phnom Penh6304ABCD",
                          "qrImage":"iVBORw0KGgoAAAANSUhEUgAAAMgAAADIAQMAAACfor5yAAAABlBMVEX///8AAABVwtN+AAAACXBIWXMAAA7EAAAOxAGVKw4bAAAA",
                          "abapay_deeplink":"abaapp://pay",
                          "expire_in":1772594519
                        }
                        """, MediaType.APPLICATION_JSON));

        PaymentIntent intent = gateway.createPayment(request());

        assertThat(intent.qrString())
                .isEqualTo("00020101021229370016bakong@aba520458125303840540410.505802KH5912GameTopUp KH6010Phnom Penh6304ABCD");
        assertThat(intent.qrImageUrl())
                .isEqualTo("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAMgAAADIAQMAAACfor5yAAAABlBMVEX///8AAABVwtN+AAAACXBIWXMAAA7EAAAOxAGVKw4bAAAA");
        assertThat(intent.status()).isEqualTo(PaymentStatus.PENDING);
        server.verify();
    }

    @Test
    void checkMapsDirectNumericStatusToCompleted() {
        server.expect(requestTo(BASE_URL + AbaPayWayProperties.OFFICIAL_STATUS_PATH))
                .andRespond(withSuccess("""
                        {
                          "status": 0,
                          "description": "Approved",
                          "amount": 10.50,
                          "currency": "USD",
                          "tran_id": "provider-direct"
                        }
                        """, MediaType.APPLICATION_JSON));

        PaymentIntent intent = gateway.checkStatus("provider-direct");

        assertThat(intent.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(intent.amount()).isEqualByComparingTo("10.50");
        assertThat(intent.currency()).isEqualTo("USD");
        server.verify();
    }

    private PaymentRequest request() {
        return new PaymentRequest("order-1", new BigDecimal("10.50"), "USD", "player-1",
                CALLBACK_URL, "Test order");
    }
}
