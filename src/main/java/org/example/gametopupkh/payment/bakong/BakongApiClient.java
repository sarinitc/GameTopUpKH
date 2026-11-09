package org.example.gametopupkh.payment.bakong;

import java.util.function.Supplier;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckAccountRequest;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckAccountResponse;
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
 * Authenticates using a Bearer token and performs read-only lookups:
 * {@code /v1/check_transaction_by_md5} and {@code /v1/check_bakong_account}.
 * Transient network and 5xx failures are retried at most {@code payment.bakong.max-retries}
 * times; authentication, rate-limit and validation failures are never retried.
 */
@Component
public class BakongApiClient {

    private static final Logger log = LoggerFactory.getLogger(BakongApiClient.class);
    private static final String GATEWAY_NAME = "bakong";
    private static final long BASE_RETRY_BACKOFF_MS = 100L;

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

        ensureTransactionCheckAllowed();
        return withRetries("check_transaction_by_md5",
                () -> post(BakongProperties.CHECK_TRANSACTION_PATH,
                        new BakongCheckTransactionRequest(md5.trim()),
                        BakongCheckTransactionResponse.class));
    }

    /**
     * Verifies that a Bakong account exists and can receive payments.
     * This is a read-only lookup; it does not move money.
     *
     * @param accountId Bakong account ID, for example {@code merchant@bank}
     * @return typed response from Bakong; {@code responseCode == 0} means verified
     */
    public BakongCheckAccountResponse checkAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACCOUNT_ID_REQUIRED",
                    "Bakong account ID is required to run account verification");
        }

        requireToken();
        return withRetries("check_bakong_account",
                () -> post(BakongProperties.CHECK_ACCOUNT_PATH,
                        new BakongCheckAccountRequest(accountId.trim()),
                        BakongCheckAccountResponse.class));
    }

    /**
     * Transaction lookups can change a payment to COMPLETED, so production
     * requires the explicit live-payment switch.
     */
    private void ensureTransactionCheckAllowed() {
        if (properties.isProduction() && !properties.isEnabled()) {
            throw new PaymentGatewayException(GATEWAY_NAME, "Live Bakong payment verification is disabled by configuration");
        }
        requireToken();
    }

    /**
     * The account lookup is read-only and admin-only, so it stays available while
     * live payments are disabled. Operators need it to prove that the production
     * token and receiving account are valid <em>before</em> flipping the switch.
     */
    private void requireToken() {
        String bearerToken = properties.apiToken();
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BAKONG_TOKEN_MISSING",
                    "Bakong API token is not configured");
        }
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        String bearerToken = properties.apiToken();
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .body(body)
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
                        throw new BakongTransientException(
                                "Bakong API returned server error " + response.getStatusCode().value());
                    })
                    .body(responseType);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            log.warn("Bakong API unauthorized: {}", e.getMessage());
            throw new PaymentGatewayException(GATEWAY_NAME, "Bakong API authentication failed. Verify API token.", e);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BAKONG_RATE_LIMIT_EXCEEDED",
                    "Bakong API rate limit exceeded; retry after delay");
        } catch (ResourceAccessException e) {
            log.warn("Timeout or network error connecting to Bakong API: {}", e.getMessage());
            throw new BakongTransientException("Timeout or connection error reading Bakong API", e);
        } catch (HttpServerErrorException e) {
            throw new BakongTransientException(
                    "Bakong API returned server error " + e.getStatusCode().value(), e);
        } catch (PaymentGatewayException | ApiException | BakongTransientException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Unexpected error calling Bakong API: {}", e.getMessage());
            throw new PaymentGatewayException(GATEWAY_NAME, "Bakong API lookup failed: " + e.getMessage(), e);
        }
    }

    private <T> T withRetries(String operation, Supplier<T> call) {
        int maxAttempts = Math.max(1, properties.maxRetries() + 1);
        BakongTransientException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (BakongTransientException e) {
                lastFailure = e;
                if (attempt >= maxAttempts) {
                    break;
                }
                log.warn("Transient Bakong {} failure (attempt {}/{}): {}",
                        operation, attempt, maxAttempts, e.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
        throw new PaymentGatewayException(GATEWAY_NAME,
                "Bakong " + operation + " failed after " + maxAttempts + " attempt(s): "
                        + (lastFailure == null ? "unknown error" : lastFailure.getMessage()),
                lastFailure);
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(BASE_RETRY_BACKOFF_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentGatewayException(GATEWAY_NAME, "Interrupted while retrying Bakong API call", e);
        }
    }

    /**
     * Internal marker for failures that are safe to retry because the lookup is read-only.
     */
    private static final class BakongTransientException extends RuntimeException {
        private BakongTransientException(String message) {
            super(message);
        }

        private BakongTransientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
