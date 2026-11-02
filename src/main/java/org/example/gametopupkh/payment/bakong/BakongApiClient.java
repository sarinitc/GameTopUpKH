package org.example.gametopupkh.payment.bakong;

import java.time.Duration;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionRequest;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for Bakong Open API.
 * Authenticates using a Bearer token and verifies transactions via {@code /v1/check_transaction_by_md5}.
 */
@Component
public class BakongApiClient {

    private static final Logger log = LoggerFactory.getLogger(BakongApiClient.class);
    private static final String GATEWAY_NAME = "bakong";

    private final RestClient restClient;
    private final BakongProperties properties;

    public BakongApiClient(RestClient.Builder restClientBuilder, BakongProperties properties) {
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

    /**
     * Checks transaction status by QR MD5 hash using the Bakong Open API.
     *
     * @param md5 32-character hexadecimal MD5 hash of the KHQR code
     * @return typed response from Bakong
     */
    public BakongCheckTransactionResponse checkTransaction(String md5) {
        if (md5 == null || md5.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MD5_REQUIRED", "QR MD5 hash is required to check Bakong transaction");
        }

        if (properties.isProduction() && !properties.isEnabled()) {
            throw new PaymentGatewayException(GATEWAY_NAME, "Live Bakong payment verification is disabled by configuration");
        }

        String bearerToken = properties.apiToken();
        if (properties.isEnabled() && (bearerToken == null || bearerToken.isBlank())) {
            throw new PaymentGatewayException(GATEWAY_NAME, "Bakong API token is not configured");
        }

        try {
            return restClient.post()
                    .uri(BakongProperties.CHECK_TRANSACTION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .body(new BakongCheckTransactionRequest(md5.trim()))
                    .retrieve()
                    .onStatus(status -> status.value() == 401 || status.value() == 403, (request, response) -> {
                        log.warn("Bakong API authentication failure (HTTP {})", response.getStatusCode().value());
                        throw new PaymentGatewayException(GATEWAY_NAME, "Bakong API authentication failed. Verify API token.");
                    })
                    .onStatus(status -> status.value() == 429, (request, response) -> {
                        log.warn("Bakong API rate limit reached (HTTP 429)");
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BAKONG_RATE_LIMIT_EXCEEDED",
                                "Bakong API rate limit exceeded; retry after delay");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        log.warn("Bakong API server error (HTTP {})", response.getStatusCode().value());
                        throw new PaymentGatewayException(GATEWAY_NAME, "Bakong API returned server error " + response.getStatusCode().value());
                    })
                    .body(BakongCheckTransactionResponse.class);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            log.warn("Bakong API unauthorized: {}", e.getMessage());
            throw new PaymentGatewayException(GATEWAY_NAME, "Bakong API authentication failed. Verify API token.", e);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BAKONG_RATE_LIMIT_EXCEEDED",
                    "Bakong API rate limit exceeded; retry after delay");
        } catch (ResourceAccessException e) {
            log.warn("Timeout or network error connecting to Bakong API: {}", e.getMessage());
            throw new PaymentGatewayException(GATEWAY_NAME, "Timeout or connection error reading Bakong API", e);
        } catch (PaymentGatewayException | ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Unexpected error checking Bakong transaction: {}", e.getMessage());
            throw new PaymentGatewayException(GATEWAY_NAME, "Bakong check-transaction lookup failed: " + e.getMessage(), e);
        }
    }

    public BakongProperties properties() {
        return properties;
    }
}
