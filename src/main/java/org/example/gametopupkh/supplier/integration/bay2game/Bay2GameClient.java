package org.example.gametopupkh.supplier.integration.bay2game;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.example.gametopupkh.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.example.gametopupkh.supplier.integration.bay2game.Bay2GameResponses.*;

/** Read-only adapter. Deliberately cannot be registered as a fulfillment SupplierClient. */
public final class Bay2GameClient {
    static final String BASE_URL = "https://api.bay2game.xyz/api";
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final RestClient http;
    private final String apiKey;

    // Package-private injection boundary for mocked HTTP tests. Production uses the pinned configuration.
    Bay2GameClient(RestClient http, String apiKey) {
        if (apiKey == null || apiKey.isBlank() || !apiKey.equals(apiKey.strip())
                || apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Bay2Game requires a rotated server environment credential");
        }
        this.http = http;
        this.apiKey = apiKey;
    }

    public Profile profile() {
        JsonNode user = object(get(ReadEndpoint.PROFILE, null), "user");
        return new Profile(integer(user, "id"), requiredText(user, "username"), money(user, "balance"),
                requiredText(user, "status"), requiredText(user, "role"), integer(user, "total_orders"),
                money(user, "total_spent"), Instant.now());
    }

    public Balance balance() {
        Profile profile = profile();
        return new Balance(profile.balance(), null, profile.fetchedAt());
    }

    public Categories categories() {
        JsonNode root = get(ReadEndpoint.CATEGORIES, null);
        List<Category> categories = new ArrayList<>();
        for (JsonNode category : array(root, "categories")) {
            List<String> fields = new ArrayList<>();
            for (JsonNode field : array(category, "game_fields")) {
                if (!field.isString() || field.asString().isBlank()) throw invalidResponse();
                fields.add(field.asString());
            }
            categories.add(new Category(requiredText(category, "game_code"), requiredText(category, "name"),
                    optionalText(category, "description"), optionalText(category, "image_url"), List.copyOf(fields)));
        }
        long total = integer(root, "total");
        if (total != categories.size()) throw invalidResponse();
        return new Categories(categories.size(), List.copyOf(categories), Instant.now());
    }

    public Products products(String gameCode) {
        if (gameCode == null || !gameCode.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GAME_CODE",
                    "A valid supplier game code is required");
        }
        JsonNode root = get(ReadEndpoint.PRODUCTS, gameCode);
        JsonNode game = object(root, "game");
        if (!gameCode.equals(requiredText(game, "game_code"))) throw invalidResponse();
        List<Product> products = new ArrayList<>();
        for (JsonNode product : array(root, "products")) {
            products.add(new Product(integer(product, "id"), requiredText(product, "product_code"),
                    requiredText(product, "name"), money(product, "sell_price"), requiredText(product, "status")));
        }
        return new Products(new Game(gameCode, requiredText(game, "name"), optionalText(game, "description"),
                optionalText(game, "image_url")), List.copyOf(products), "USD", Instant.now());
    }

    private JsonNode get(ReadEndpoint endpoint, String gameCode) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(BASE_URL + endpoint.path)
                .queryParam("api_key", "{key}");
        if (gameCode != null) uri.queryParam("game_code", "{game}");
        URI target = uri.encode().buildAndExpand(gameCode == null
                ? Map.of("key", apiKey) : Map.of("key", apiKey, "game", gameCode)).toUri();
        try {
            return http.get().uri(target).accept(MediaType.APPLICATION_JSON).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 401 || status == 403) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "BAY2GAME_AUTH_FAILED",
                            "Supplier authentication was rejected");
                }
                if (status == 429) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BAY2GAME_RATE_LIMITED",
                            "Supplier rate limit reached; try again later");
                }
                if (status < 200 || status >= 300) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "BAY2GAME_UPSTREAM_ERROR",
                            "Supplier returned an unsuccessful HTTP response");
                }
                byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) throw invalidResponse();
                JsonNode root = JSON.readTree(body);
                if (root == null || !root.isObject()) throw invalidResponse();
                String providerStatus = requiredText(root, "status");
                if (!"SUCCESS".equals(providerStatus)) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "BAY2GAME_REJECTED",
                            "Supplier rejected the read request");
                }
                return root;
            });
        } catch (ResourceAccessException e) {
            // Never retain the cause: transport errors can contain the credential-bearing URI.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BAY2GAME_UNAVAILABLE",
                    "Supplier is temporarily unavailable");
        } catch (JacksonException e) {
            throw invalidResponse();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "BAY2GAME_UPSTREAM_ERROR",
                    "Supplier returned an unusable response");
        }
    }

    private JsonNode object(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isObject()) throw invalidResponse();
        return value;
    }

    private JsonNode array(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) throw invalidResponse();
        return value;
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) throw invalidResponse();
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString()) throw invalidResponse();
        return value.asString();
    }

    private long integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalidResponse();
        return value.longValue();
    }

    private BigDecimal money(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) throw invalidResponse();
        BigDecimal amount = value.decimalValue();
        if (amount.signum() < 0) throw invalidResponse();
        return amount;
    }

    private ApiException invalidResponse() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "BAY2GAME_INVALID_RESPONSE",
                "Supplier returned an invalid response");
    }

    private enum ReadEndpoint {
        PROFILE("/profile"), CATEGORIES("/categories"), PRODUCTS("/products");
        private final String path;
        ReadEndpoint(String path) { this.path = path; }
    }
}
