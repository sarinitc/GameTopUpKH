package org.example.gametopupkh.payment.bakong;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bakong KHQR configuration supporting sandbox and production environments.
 * Production payments fail closed unless enabled is explicitly set to true
 * with valid credentials on approved NBC Bakong domains.
 */
@Component
public class BakongProperties {

    public static final String DEFAULT_BASE_URL = "https://api-bakong.nbc.gov.kh";
    public static final String DEFAULT_SANDBOX_BASE_URL = "https://sit-api-bakong.nbc.org.kh";
    public static final String CHECK_TRANSACTION_PATH = "/v1/check_transaction_by_md5";

    private static final Set<String> APPROVED_PRODUCTION_DOMAINS = Set.of(
            "api-bakong.nbc.gov.kh",
            "bakong.nbc.gov.kh",
            "api-bakong.nbc.org.kh",
            "bakong.nbc.org.kh"
    );

    private static final Set<String> ALLOWED_TEST_DOMAINS = Set.of(
            "localhost",
            "127.0.0.1",
            "example.com",
            "example.test",
            "api-test.nbc.gov.kh"
    );

    private final String environment;
    private final boolean enabled;
    private final String baseUrl;
    private final String apiToken;
    private final String accountId;
    private final String merchantName;
    private final String merchantCity;
    private final int lifetimeMinutes;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxRetries;

    @Autowired
    public BakongProperties(
            @Value("${payment.bakong.environment:production}") String environment,
            @Value("${payment.bakong.enabled:false}") String enabled,
            @Value("${payment.bakong.base-url:https://api-bakong.nbc.gov.kh}") String baseUrl,
            @Value("${payment.bakong.api-token:}") String apiToken,
            @Value("${payment.bakong.account-id:}") String accountId,
            @Value("${payment.bakong.merchant-name:GameTopUp-KH}") String merchantName,
            @Value("${payment.bakong.merchant-city:Phnom Penh}") String merchantCity,
            @Value("${payment.bakong.lifetime-minutes:10}") int lifetimeMinutes,
            @Value("${payment.bakong.connect-timeout:5s}") Duration connectTimeout,
            @Value("${payment.bakong.read-timeout:15s}") Duration readTimeout,
            @Value("${payment.bakong.max-retries:2}") int maxRetries) {
        this(environment, parseBooleanLenient(enabled), baseUrl, apiToken, accountId, merchantName, merchantCity,
                lifetimeMinutes, connectTimeout, readTimeout, maxRetries);
    }

    public BakongProperties(
            String environment,
            boolean enabled,
            String baseUrl,
            String apiToken,
            String accountId,
            String merchantName,
            String merchantCity,
            int lifetimeMinutes,
            Duration connectTimeout,
            Duration readTimeout,
            int maxRetries) {
        this.environment = environment == null ? "production" : environment.trim().toLowerCase(Locale.ROOT);
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE_URL : baseUrl.trim();
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.accountId = accountId == null ? "" : accountId.trim();
        this.merchantName = merchantName == null ? "GameTopUp-KH" : merchantName.trim();
        this.merchantCity = merchantCity == null ? "Phnom Penh" : merchantCity.trim();
        this.lifetimeMinutes = Math.min(Math.max(1, lifetimeMinutes), 10);
        this.connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
        this.readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(15);
        this.maxRetries = Math.max(0, maxRetries);
        validateConfiguration();
    }

    public static boolean parseBooleanLenient(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(trimmed) || "1".equals(trimmed) || "yes".equals(trimmed) || "on".equals(trimmed);
    }

    public BakongProperties(String baseUrl, String apiToken, String accountId, String merchantName) {
        this("production", false, baseUrl, apiToken, accountId, merchantName, "Phnom Penh",
                10, Duration.ofSeconds(5), Duration.ofSeconds(15), 2);
    }

    @PostConstruct
    public void validateConfiguration() {
        if (enabled) {
            if (isProduction()) {
                if (apiToken.isBlank() || isPlaceholderToken(apiToken)) {
                    throw new IllegalStateException("BAKONG_API_TOKEN must be configured with a valid token when Bakong payments are enabled.");
                }
                if (accountId.isBlank()) {
                    throw new IllegalStateException("BAKONG_ACCOUNT_ID must be configured when Bakong payments are enabled.");
                }
                if (merchantName.isBlank()) {
                    throw new IllegalStateException("BAKONG_MERCHANT_NAME must be configured when Bakong payments are enabled.");
                }
                validateApprovedDomain(baseUrl);
            } else {
                if (apiToken.isBlank() || isPlaceholderToken(apiToken)) {
                    throw new IllegalStateException("payment.bakong.api-token must be configured when Bakong payments are enabled.");
                }
            }
        }
    }

    private boolean isPlaceholderToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.contains("placeholder") || lower.contains("change_me") || lower.contains("your_token");
    }

    private void validateApprovedDomain(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return;
            }
            String hostLower = host.toLowerCase(Locale.ROOT);
            if (APPROVED_PRODUCTION_DOMAINS.contains(hostLower)) {
                return;
            }
            if (ALLOWED_TEST_DOMAINS.contains(hostLower) || hostLower.endsWith(".test") || hostLower.endsWith(".example")) {
                return;
            }
            throw new IllegalStateException("Base URL '" + url + "' host '" + host
                    + "' is not an approved Bakong domain for production.");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid Bakong base URL: " + url, e);
        }
    }

    public boolean isProduction() {
        return "production".equalsIgnoreCase(environment) || "prod".equalsIgnoreCase(environment);
    }

    public boolean isSandbox() {
        return !isProduction();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiToken() {
        return apiToken;
    }

    public String accountId() {
        return accountId;
    }

    public boolean hasAccountId() {
        return accountId != null && !accountId.isBlank();
    }

    public String merchantName() {
        return merchantName;
    }

    public String merchantCity() {
        return merchantCity;
    }

    public int lifetimeMinutes() {
        return lifetimeMinutes;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    @Override
    public String toString() {
        return "BakongProperties{" +
                "environment='" + environment + '\'' +
                ", enabled=" + enabled +
                ", baseUrl='" + baseUrl + '\'' +
                ", apiToken='[REDACTED]'" +
                ", accountId='" + (accountId.isBlank() ? "[NOT_SET]" : accountId) + '\'' +
                ", merchantName='" + merchantName + '\'' +
                ", merchantCity='" + merchantCity + '\'' +
                ", lifetimeMinutes=" + lifetimeMinutes +
                '}';
    }
}
