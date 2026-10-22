package org.example.gametopupkh.payment.aba;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.example.gametopupkh.payment.aba.dto.AbaCheckTransactionRequest;
import org.example.gametopupkh.payment.aba.dto.AbaCheckTransactionResponse;
import org.example.gametopupkh.payment.aba.dto.AbaQrRequest;
import org.example.gametopupkh.payment.aba.dto.AbaQrResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * HTTP transport for ABA PayWay. The official defaults use the generate-QR and
 * check-transaction-2 JSON APIs with an HMAC-SHA512 request hash. Explicitly
 * configured non-official paths keep the legacy configurable adapter behavior.
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "aba")
public class AbaPayWayClient {

    private static final DateTimeFormatter REQUEST_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final RestClient restClient;
    private final AbaPayWayProperties properties;

    public AbaPayWayClient(RestClient.Builder restClientBuilder, AbaPayWayProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
    }

    public AbaQrResponse createPayment(PaymentRequest request) {
        if (properties.usesOfficialCreateApi()) {
            return createOfficialPayment(request);
        }
        JsonNode response = restClient.post()
                .uri(properties.createPath())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-KEY", properties.apiKey())
                .body(new AbaQrRequest(
                        request.transactionId(),
                        request.amount(),
                        request.currency(),
                        request.customerReference(),
                        request.callbackUrl(),
                        request.description(),
                        properties.merchantReference()))
                .retrieve()
                .body(JsonNode.class);
        return new AbaQrResponse(response);
    }

    public AbaCheckTransactionResponse checkTransaction(AbaCheckTransactionRequest request) {
        if (properties.usesOfficialStatusApi()) {
            return checkOfficialTransaction(request);
        }
        JsonNode response = restClient.get()
                .uri(properties.statusPath().replace("{reference}", request.gatewayReference()))
                .header("X-API-KEY", properties.apiKey())
                .retrieve()
                .body(JsonNode.class);
        return new AbaCheckTransactionResponse(response);
    }

    void requireConfigured() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new PaymentGatewayException(AbaPayWayGateway.NAME, "payment.aba.api-key is not configured");
        }
        if ((properties.usesOfficialCreateApi() || properties.usesOfficialStatusApi())
                && (properties.merchantReference() == null || properties.merchantReference().isBlank())) {
            throw new PaymentGatewayException(AbaPayWayGateway.NAME,
                    "payment.aba.merchant-id is not configured");
        }
    }

    private AbaQrResponse createOfficialPayment(PaymentRequest request) {
        String requestTime = REQUEST_TIME.format(Instant.now());
        String merchantId = properties.merchantReference();
        String reference = request.transactionId();
        String amount = request.amount() == null ? "" : request.amount().toPlainString();
        String currency = request.currency() == null ? "" : request.currency();
        String paymentOption = properties.paymentOption();
        String callbackUrl = encodeCallbackUrl(request.callbackUrl());
        String purchaseType = "purchase";
        String lifetime = String.valueOf(properties.lifetimeMinutes());
        String qrImageTemplate = properties.qrImageTemplate();

        String hash = hash(requestTime + merchantId + reference + amount
                + "" + "" + "" + "" + ""
                + purchaseType + paymentOption + callbackUrl
                + "" + currency + "" + "" + ""
                + lifetime + qrImageTemplate);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("req_time", requestTime);
        body.put("merchant_id", merchantId);
        body.put("tran_id", reference);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("purchase_type", purchaseType);
        body.put("payment_option", paymentOption);
        if (callbackUrl != null) {
            body.put("callback_url", callbackUrl);
        }
        body.put("lifetime", properties.lifetimeMinutes());
        body.put("qr_image_template", qrImageTemplate);
        body.put("hash", hash);

        JsonNode response = restClient.post()
                .uri(properties.createPath())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-KEY", properties.apiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return new AbaQrResponse(response);
    }

    private AbaCheckTransactionResponse checkOfficialTransaction(AbaCheckTransactionRequest request) {
        String requestTime = REQUEST_TIME.format(Instant.now());
        String merchantId = properties.merchantReference();
        String reference = request.gatewayReference();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("req_time", requestTime);
        body.put("merchant_id", merchantId);
        body.put("tran_id", reference);
        body.put("hash", hash(requestTime + merchantId + reference));

        JsonNode response = restClient.post()
                .uri(properties.statusPath())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-KEY", properties.apiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return new AbaCheckTransactionResponse(response);
    }

    private String hash(String payload) {
        return AbaPayWayHashService.hmacSha512Base64(properties.apiKey(), payload);
    }

    private String encodeCallbackUrl(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return "";
        }
        return Base64.getEncoder().encodeToString(callbackUrl.getBytes(StandardCharsets.UTF_8));
    }
}
