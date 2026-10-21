package org.example.gametopupkh.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * AbaPayWay gateway backed by the provider REST API. The endpoint paths and the
 * payload mapping are configurable so they can be aligned with the merchant
 * account in use.
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "aba")
public class AbaPayWayGateway implements PaymentGateway {

    public static final String NAME = "aba";

    private final RestClient restClient;
    private final String apiKey;
    private final String createPath;
    private final String statusPath;
    private final String merchantReference;

    public AbaPayWayGateway(
            RestClient.Builder restClientBuilder,
            @Value("${payment.aba.base-url:https://api.abapayway.com}") String baseUrl,
            @Value("${payment.aba.api-key:}") String apiKey,
            @Value("${payment.aba.create-path:/api/transactions}") String createPath,
            @Value("${payment.aba.status-path:/api/transactions/{reference}}") String statusPath,
            @Value("${payment.aba.merchant-id:}") String merchantReference) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.createPath = createPath;
        this.statusPath = statusPath;
        this.merchantReference = merchantReference;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PaymentIntent createPayment(PaymentRequest request) {
        requireConfigured();
        try {
            JsonNode response = restClient.post()
                    .uri(createPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-API-KEY", apiKey)
                    .body(new CreateTransactionBody(
                            request.orderId(),
                            request.amount(),
                            request.currency(),
                            request.customerReference(),
                            request.callbackUrl(),
                            request.description(),
                            merchantReference))
                    .retrieve()
                    .body(JsonNode.class);
            return toIntent(response, request);
        } catch (Exception e) {
            throw new PaymentGatewayException(NAME, "Failed to create AbaPayWay payment: " + e.getMessage(), e);
        }
    }

    @Override
    public PaymentIntent checkStatus(String gatewayReference) {
        requireConfigured();
        try {
            JsonNode response = restClient.get()
                    .uri(statusPath.replace("{reference}", gatewayReference))
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .body(JsonNode.class);
            return toIntent(response, null);
        } catch (Exception e) {
            throw new PaymentGatewayException(NAME, "Failed to read AbaPayWay payment: " + e.getMessage(), e);
        }
    }

    private PaymentIntent toIntent(JsonNode response, PaymentRequest request) {
        if (response == null || response.isNull()) {
            throw new PaymentGatewayException(NAME, "Empty response from AbaPayWay");
        }
        String reference = text(response, "transaction_id", "reference", "id");
        if (reference == null) {
            throw new PaymentGatewayException(NAME, "AbaPayWay response did not contain a transaction reference");
        }
        BigDecimal amount = request != null ? request.amount() : decimal(response, "amount");
        String currency = request != null ? request.currency() : text(response, "currency");
        return new PaymentIntent(
                NAME,
                reference,
                mapStatus(text(response, "status", "state")),
                amount,
                currency,
                text(response, "redirect_url", "payment_url", "qr_url"),
                Instant.now());
    }

    private PaymentStatus mapStatus(String status) {
        if (status == null) {
            return PaymentStatus.PENDING;
        }
        return switch (status.toUpperCase()) {
            case "PAID", "COMPLETED", "SUCCESS" -> PaymentStatus.COMPLETED;
            case "FAILED", "ERROR" -> PaymentStatus.FAILED;
            case "EXPIRED" -> PaymentStatus.EXPIRED;
            case "CANCELLED", "VOIDED" -> PaymentStatus.CANCELLED;
            default -> PaymentStatus.PENDING;
        };
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.decimalValue();
    }

    private void requireConfigured() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new PaymentGatewayException(NAME, "payment.aba.api-key is not configured");
        }
    }

    private record CreateTransactionBody(
            String orderId,
            BigDecimal amount,
            String currency,
            String customerReference,
            String callbackUrl,
            String description,
            String merchantId) {
    }
}
