package org.example.gametopupkh.player.integration.volsever;

import java.io.IOException;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.player.PlayerAccountValidator;
import org.example.gametopupkh.player.dto.PlayerVerificationStatus;
import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;
import org.example.gametopupkh.player.integration.PlayerLookupClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only Volsever Game ID Checker adapter.
 *
 * <p>Contract: {@code GET https://gate.volsever.com/proxy/api/game/<slug>?id=...}
 * with the {@code X-API-Key} header. Free Fire uses {@code free-fire-global};
 * Mobile Legends also sends {@code zone}. Only the documented nickname and
 * region are returned; the raw provider envelope is never exposed.</p>
 *
 * <p>Lookups are never retried. A 404 is a normal "account not found" answer;
 * authentication, balance, rate-limit, transport and malformed-response
 * failures fail closed as {@code UNAVAILABLE} through the caller's existing
 * sanitisation.</p>
 */
public final class VolseverPlayerLookupClient implements PlayerLookupClient {

    static final String PROVIDER = "VOLSEVER";
    private static final int MAX_RESPONSE_BYTES = 65_536;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final RestClient http;
    private final VolseverProperties properties;

    public VolseverPlayerLookupClient(RestClient http, VolseverProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public boolean supports(String gameSlug) {
        return PlayerAccountValidator.supportsGame(gameSlug);
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public PlayerVerifyResponse lookup(String gameSlug, PlayerVerifyRequest request) {
        String path = pathFor(gameSlug);
        if (path == null || request == null || request.playerId() == null) {
            return unavailable(request);
        }

        try {
            return http.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path(path)
                                .queryParam("id", request.playerId());
                        if (request.zoneId() != null) {
                            builder.queryParam("zone", request.zoneId());
                        }
                        return builder.build();
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((httpRequest, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            return new PlayerVerifyResponse(PlayerVerificationStatus.INVALID,
                                    request.playerId(), request.zoneId(), null, null, PROVIDER);
                        }
                        if (status == 400) {
                            throw providerError(HttpStatus.BAD_GATEWAY, "VOLSEVER_INVALID_REQUEST",
                                    "Volsever rejected the lookup request");
                        }
                        if (status == 401 || status == 403) {
                            throw providerError(HttpStatus.BAD_GATEWAY, "VOLSEVER_AUTH_FAILED",
                                    "Volsever authentication was rejected");
                        }
                        if (status == 402) {
                            throw providerError(HttpStatus.SERVICE_UNAVAILABLE,
                                    "VOLSEVER_INSUFFICIENT_BALANCE",
                                    "Volsever account balance is insufficient");
                        }
                        if (status == 429) {
                            throw providerError(HttpStatus.SERVICE_UNAVAILABLE,
                                    "VOLSEVER_RATE_LIMITED",
                                    "Volsever rate limit reached; try again later");
                        }
                        if (status < 200 || status >= 300) {
                            throw providerError(HttpStatus.BAD_GATEWAY, "VOLSEVER_UPSTREAM_ERROR",
                                    "Volsever returned an unsuccessful HTTP response");
                        }
                        return parseVerified(request, response.getBody());
                    });
        } catch (ResourceAccessException ex) {
            // Never retain the cause: transport failures can echo request details.
            throw providerError(HttpStatus.SERVICE_UNAVAILABLE, "VOLSEVER_UNAVAILABLE",
                    "Volsever is temporarily unavailable");
        } catch (JacksonException ex) {
            throw invalidResponse();
        } catch (RestClientException ex) {
            throw providerError(HttpStatus.BAD_GATEWAY, "VOLSEVER_UPSTREAM_ERROR",
                    "Volsever returned an unusable response");
        }
    }

    private PlayerVerifyResponse parseVerified(PlayerVerifyRequest request, java.io.InputStream body)
            throws IOException {
        if (body == null) {
            throw invalidResponse();
        }
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
            throw invalidResponse();
        }

        JsonNode root = JSON.readTree(bytes);
        if (root == null || !root.isObject()) {
            throw invalidResponse();
        }
        JsonNode status = root.path("status");
        if (!status.isBoolean() || !status.booleanValue()) {
            throw invalidResponse();
        }
        JsonNode code = root.path("code");
        if (!code.isIntegralNumber() || code.intValue() != 200) {
            throw invalidResponse();
        }

        JsonNode data = root.path("data");
        if (!data.isObject()) {
            throw invalidResponse();
        }

        String nickname = requiredText(data, "username");
        String userId = identifier(data, "user_id");
        if (userId == null || !userId.equals(request.playerId())) {
            throw invalidResponse();
        }

        String zone = identifier(data, "zone");
        if (request.zoneId() == null) {
            if (zone != null && !zone.isBlank()) {
                throw invalidResponse();
            }
        } else if (!request.zoneId().equals(zone)) {
            throw invalidResponse();
        }

        String region = optionalText(data, "region");
        return new PlayerVerifyResponse(PlayerVerificationStatus.VERIFIED, request.playerId(),
                request.zoneId(), nickname, region, PROVIDER);
    }

    private String pathFor(String gameSlug) {
        if (gameSlug == null) {
            return null;
        }
        return switch (gameSlug.trim().toLowerCase()) {
            case "free-fire" -> properties.getFreeFirePath();
            case "mobile-legends" -> properties.getMobileLegendsPath();
            default -> null;
        };
    }

    private PlayerVerifyResponse unavailable(PlayerVerifyRequest request) {
        String playerId = request == null ? null : request.playerId();
        String zoneId = request == null ? null : request.zoneId();
        return new PlayerVerifyResponse(PlayerVerificationStatus.UNAVAILABLE, playerId, zoneId,
                null, null, PROVIDER);
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw invalidResponse();
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw invalidResponse();
        }
        return value.asString();
    }

    private String identifier(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.asString();
        }
        throw invalidResponse();
    }

    private ApiException invalidResponse() {
        return providerError(HttpStatus.BAD_GATEWAY, "VOLSEVER_INVALID_RESPONSE",
                "Volsever returned an invalid response");
    }

    private ApiException providerError(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }
}
