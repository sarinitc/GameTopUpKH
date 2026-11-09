package org.example.gametopupkh.supplier.integration.vsc;

import java.math.BigDecimal;
import java.net.URI;
import java.util.regex.Pattern;

import org.example.gametopupkh.supplier.integration.SupplierClient;
import org.example.gametopupkh.supplier.integration.SupplierTopupRequest;
import org.example.gametopupkh.supplier.integration.SupplierTopupResponse;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * VSC Games top-up adapter for Free Fire. Implements the documented contract:
 * {@code GET /freefire-merchant-topup?productId=&playerID=&Merchantname=} with a
 * {@code merchantkey} header. A top-up counts as delivered only when the provider
 * answers HTTP 2xx with {@code code == 200} and a {@code status} of {@code Completed};
 * any other payload, an ambiguous status, a transport failure, or an unusable
 * response raises {@link SupplierException}, which the fulfillment state machine
 * records as an outcome requiring human review instead of retrying blindly.
 *
 * <p>The API uses GET with side effects. Whether repeating the request re-executes
 * the top-up must be confirmed with the provider before live use; this adapter
 * performs no retries of its own and never trusts a provider-reported amount for
 * the ledger. The provider's {@code orderId} is returned as the supplier reference
 * for fulfillment audit and reconciliation.</p>
 */
public final class VscSupplierClient implements SupplierClient {

    public static final String KEY = "vsc";
    static final String BASE_URL = "https://api.vscgames.com";
    static final String TOP_UP_PATH = "/freefire-merchant-topup";
    /** Product IDs and Free Fire player IDs are decimal digits only. */
    static final Pattern NUMERIC_ID = Pattern.compile("^[0-9]{1,20}$");
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final RestClient http;
    private final String apiKey;
    private final String merchantName;

    // Package-private injection boundary for mocked HTTP tests. Production uses the pinned configuration.
    VscSupplierClient(RestClient http, String apiKey, String merchantName) {
        if (apiKey == null || apiKey.isBlank() || !apiKey.equals(apiKey.strip())
                || apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("VSC requires a rotated server environment credential");
        }
        String name = merchantName == null ? "" : merchantName.trim();
        if (name.isBlank() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("VSC requires a control-character-free merchant name of at most 100 characters");
        }
        this.http = http;
        this.apiKey = apiKey;
        this.merchantName = name;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "VSC";
    }

    @Override
    public SupplierTopupResponse fulfill(SupplierTopupRequest request) {
        Validation validation = validate(request);
        if (validation != null) {
            return SupplierTopupResponse.failure(validation.message());
        }
        String playerId = request.playerId().trim();
        String productId = request.externalSku().trim();

        URI target = UriComponentsBuilder.fromUriString(BASE_URL + TOP_UP_PATH)
                .queryParam("productId", productId)
                .queryParam("playerID", playerId)
                .queryParam("Merchantname", merchantName)
                .encode().build().toUri();

        JsonNode root;
        try {
            root = http.get()
                    .uri(target)
                    .header("merchantkey", apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((req, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) {
                            throw new SupplierException(KEY, "Supplier authentication was rejected");
                        }
                        if (status == 429) {
                            throw new SupplierException(KEY, "Supplier rate limit reached; try again later");
                        }
                        if (status < 200 || status >= 300) {
                            throw new SupplierException(KEY, "Supplier returned HTTP " + status + "; outcome requires review");
                        }
                        byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
                            throw new SupplierException(KEY, "Supplier returned an unusable response; outcome requires review");
                        }
                        JsonNode parsed;
                        try {
                            parsed = JSON.readTree(body);
                        } catch (JacksonException e) {
                            throw new SupplierException(KEY, "Supplier returned malformed JSON; outcome requires review");
                        }
                        if (parsed == null || !parsed.isObject()) {
                            throw new SupplierException(KEY, "Supplier returned malformed JSON; outcome requires review");
                        }
                        return parsed;
                    });
        } catch (ResourceAccessException e) {
            // Never retain the cause: transport errors can embed the player-bearing URI.
            throw new SupplierException(KEY, "Supplier is temporarily unavailable; outcome requires review");
        } catch (RestClientException e) {
            throw new SupplierException(KEY, "Supplier call failed; outcome requires review");
        }

        return mapResult(root);
    }

    private SupplierTopupResponse mapResult(JsonNode root) {
        JsonNode codeNode = root.get("code");
        if (codeNode == null || !codeNode.isIntegralNumber() || !codeNode.canConvertToInt()) {
            throw invalidResponse();
        }
        int code = codeNode.intValue();

        String status = optionalText(root, "status");
        // The provider message may contain account data; never log or persist it.
        String providerOrderId = optionalText(root, "orderId");
        BigDecimal amount = optionalMoney(root, "amount");
        if (amount != null && amount.signum() < 0) {
            throw invalidResponse();
        }

        if (code == 200 && status != null && "completed".equalsIgnoreCase(status.trim())) {
            return SupplierTopupResponse.success(
                    providerOrderId == null ? null : providerOrderId.trim(),
                    "VSC top-up reported as completed");
        }
        throw new SupplierException(KEY, "VSC top-up not completed (code="
                + code + ", status=" + sanitizeStatus(status) + "); outcome requires review");
    }

    private static String sanitizeStatus(String status) {
        if (status == null) {
            return "absent";
        }
        String trimmed = status.trim();
        if (trimmed.length() > 32 || !trimmed.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ' ')) {
            return "<redacted>";
        }
        return trimmed;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw invalidResponse();
        }
        return value.asString();
    }

    private static BigDecimal optionalMoney(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw invalidResponse();
        }
        return value.decimalValue();
    }

    private static SupplierException invalidResponse() {
        return new SupplierException(KEY, "Supplier returned an invalid response; outcome requires review");
    }

    /**
     * Free Fire top-ups carry exactly one package per order. Reject anything else
     * before any outbound call so a quantity mismatch can never become an
     * unreviewed multi-charge.
     */
    private static Validation validate(SupplierTopupRequest request) {
        if (request.quantity() != 1) {
            return new Validation("Only quantity 1 can be fulfilled by this supplier");
        }
        if (!isNumericId(request.playerId())) {
            return new Validation("Player ID must be numeric for this supplier");
        }
        if (!isNumericId(request.externalSku())) {
            return new Validation("Supplier product ID (external SKU) must be numeric for this supplier");
        }
        return null;
    }

    private static boolean isNumericId(String value) {
        return value != null && NUMERIC_ID.matcher(value.trim()).matches();
    }

    private record Validation(String message) {
    }
}
