package org.example.gametopupkh.payment.aba;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.example.gametopupkh.payment.aba.dto.AbaCheckTransactionRequest;
import org.example.gametopupkh.payment.aba.dto.AbaCheckTransactionResponse;
import org.example.gametopupkh.payment.aba.dto.AbaQrRequest;
import org.example.gametopupkh.payment.aba.dto.AbaQrResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
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

    private static final Logger log = LoggerFactory.getLogger(AbaPayWayClient.class);
    private static final DateTimeFormatter REQUEST_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final RestClient restClient;
    private final AbaPayWayProperties properties;

    public AbaPayWayClient(RestClient.Builder restClientBuilder, AbaPayWayProperties properties) {
        RestClient.Builder builder = restClientBuilder.baseUrl(properties.baseUrl());
        if (!isMockRequestFactoryPresent(restClientBuilder)) {
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(properties.connectTimeout())
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(properties.readTimeout());
            builder.requestFactory(factory);
        }
        this.restClient = builder.build();
        this.properties = properties;
    }

    private static boolean isMockRequestFactoryPresent(RestClient.Builder builder) {
        try {
            java.lang.reflect.Field field = builder.getClass().getDeclaredField("requestFactory");
            field.setAccessible(true);
            Object factory = field.get(builder);
            if (factory == null) {
                return false;
            }
            String name = factory.getClass().getName();
            return name.contains("Mock") || name.startsWith("org.springframework.test.");
        } catch (Exception e) {
            return false;
        }
    }

    public AbaQrResponse createPayment(PaymentRequest request) {
        requireConfigured();
        if (properties.isProduction() && !properties.livePaymentsEnabled()) {
            throw new PaymentGatewayException(AbaPayWayGateway.NAME,
                    "Live production payments are disabled by configuration");
        }
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
        requireConfigured();
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

        int retries = properties.maxRetries();
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                JsonNode response = restClient.post()
                        .uri(properties.statusPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-KEY", properties.apiKey())
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                verifyMerchantId(response);
                return new AbaCheckTransactionResponse(response);
            } catch (PaymentGatewayException e) {
                throw e;
            } catch (ResourceAccessException e) {
                if (attempt < retries) {
                    log.warn("Transient failure calling ABA check-transaction (attempt {}/{}), retrying: {}",
                            attempt + 1, retries + 1, e.getMessage());
                    try {
                        Thread.sleep(100L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new PaymentGatewayException(AbaPayWayGateway.NAME, "Interrupted during status check", ie);
                    }
                } else {
                    throw new PaymentGatewayException(AbaPayWayGateway.NAME,
                            "ABA check-transaction network timeout/failure after retries: " + e.getMessage(), e);
                }
            } catch (Exception e) {
                throw new PaymentGatewayException(AbaPayWayGateway.NAME,
                        "ABA check-transaction request failed: " + e.getMessage(), e);
            }
        }
        throw new PaymentGatewayException(AbaPayWayGateway.NAME, "ABA check-transaction lookup failed");
    }

    private void verifyMerchantId(JsonNode response) {
        if (response == null || properties.merchantReference() == null || properties.merchantReference().isBlank()) {
            return;
        }
        String returned = text(response, "merchant_id", "merchantId");
        if (returned == null) {
            returned = nestedText(response, "data", "merchant_id");
        }
        if (returned != null && !returned.isBlank() && !returned.equalsIgnoreCase(properties.merchantReference().trim())) {
            throw new PaymentGatewayException(AbaPayWayGateway.NAME,
                    "Provider merchant ID mismatch: expected " + properties.merchantReference() + " but got " + returned);
        }
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode val = node.get(field);
            if (val != null && !val.isNull()) {
                String t = val.asText();
                if (t != null && !t.isBlank()) {
                    return t.trim();
                }
            }
        }
        return null;
    }

    private String nestedText(JsonNode node, String parent, String field) {
        JsonNode parentNode = node.get(parent);
        if (parentNode == null || !parentNode.isObject()) {
            return null;
        }
        JsonNode val = parentNode.get(field);
        if (val == null || val.isNull()) {
            return null;
        }
        String t = val.asText();
        return t == null || t.isBlank() ? null : t.trim();
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
