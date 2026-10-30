package org.example.gametopupkh.payment.aba;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ABA PayWay configuration supporting MOCK, ABA_SANDBOX, and ABA_PRODUCTION.
 * Production payments fail closed unless live-payments-enabled is explicitly set to true
 * with valid production credentials on approved PayWay domains.
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "aba")
public class AbaPayWayProperties {

    public static final String OFFICIAL_CREATE_PATH =
            "/api/payment-gateway/v1/payments/generate-qr";
    public static final String OFFICIAL_STATUS_PATH =
            "/api/payment-gateway/v1/payments/check-transaction-2";
    public static final String OFFICIAL_PURCHASE_PATH =
            "/api/payment-gateway/v1/payments/purchase";

    public static final String APPROVED_SANDBOX_DOMAIN = "checkout-sandbox.payway.com.kh";
    public static final String APPROVED_PRODUCTION_DOMAIN = "checkout.payway.com.kh";

    private static final Set<String> ALLOWED_TEST_DOMAINS = Set.of(
            "localhost",
            "127.0.0.1",
            "example.com",
            "example.test",
            "shop.example.test"
    );

    private final String environment;
    private final boolean livePaymentsEnabled;
    private final String baseUrl;
    private final String apiKey;
    private final String createPath;
    private final String statusPath;
    private final String purchasePath;
    private final String paymentOption;
    private final String qrImageTemplate;
    private final int lifetimeMinutes;
    private final String merchantReference;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxRetries;

    @Autowired
    public AbaPayWayProperties(
            @Value("${payment.aba.environment:sandbox}") String environment,
            @Value("${payment.aba.live-payments-enabled:false}") boolean livePaymentsEnabled,
            @Value("${payment.aba.base-url:https://checkout-sandbox.payway.com.kh}") String baseUrl,
            @Value("${payment.aba.api-key:}") String apiKey,
            @Value("${payment.aba.create-path:" + OFFICIAL_CREATE_PATH + "}") String createPath,
            @Value("${payment.aba.status-path:" + OFFICIAL_STATUS_PATH + "}") String statusPath,
            @Value("${payment.aba.purchase-path:" + OFFICIAL_PURCHASE_PATH + "}") String purchasePath,
            @Value("${payment.aba.payment-option:abapay_khqr}") String paymentOption,
            @Value("${payment.aba.qr-image-template:template3_color}") String qrImageTemplate,
            @Value("${payment.aba.lifetime-minutes:30}") int lifetimeMinutes,
            @Value("${payment.aba.merchant-id:}") String merchantReference,
            @Value("${payment.aba.connect-timeout:5s}") Duration connectTimeout,
            @Value("${payment.aba.read-timeout:15s}") Duration readTimeout,
            @Value("${payment.aba.max-retries:2}") int maxRetries) {
        this.environment = environment == null ? "sandbox" : environment.trim().toLowerCase(Locale.ROOT);
        this.livePaymentsEnabled = livePaymentsEnabled;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.createPath = createPath;
        this.statusPath = statusPath;
        this.purchasePath = purchasePath;
        this.paymentOption = paymentOption;
        this.qrImageTemplate = qrImageTemplate;
        this.lifetimeMinutes = lifetimeMinutes;
        this.merchantReference = merchantReference;
        this.connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
        this.readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(15);
        this.maxRetries = Math.max(0, maxRetries);
        validateConfiguration();
    }

    public AbaPayWayProperties(String baseUrl, String apiKey, String createPath, String statusPath,
            String merchantReference) {
        this("sandbox", false, baseUrl, apiKey, createPath, statusPath, OFFICIAL_PURCHASE_PATH, "abapay_khqr",
                "template3_color", 30, merchantReference, Duration.ofSeconds(5), Duration.ofSeconds(15), 2);
    }

    public AbaPayWayProperties(
            String baseUrl, String apiKey, String createPath, String statusPath,
            String purchasePath, String paymentOption, String qrImageTemplate,
            int lifetimeMinutes, String merchantReference) {
        this("sandbox", false, baseUrl, apiKey, createPath, statusPath, purchasePath, paymentOption,
                qrImageTemplate, lifetimeMinutes, merchantReference, Duration.ofSeconds(5), Duration.ofSeconds(15), 2);
    }

    @PostConstruct
    public void validateConfiguration() {
        if (isProduction()) {
            if (!livePaymentsEnabled) {
                throw new IllegalStateException(
                        "ABA PayWay live payments are disabled by configuration (payment.aba.live-payments-enabled=false). " +
                                "Production payments cannot be activated without explicit approval.");
            }
            if (merchantReference == null || merchantReference.isBlank()) {
                throw new IllegalStateException("payment.aba.merchant-id must be configured in production.");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("payment.aba.api-key must be configured in production.");
            }
            if (baseUrl != null && baseUrl.toLowerCase(Locale.ROOT).contains("sandbox")) {
                throw new IllegalStateException("Sandbox base URL cannot be used in production environment.");
            }
            if (merchantReference != null && "ec478916".equalsIgnoreCase(merchantReference.trim())) {
                throw new IllegalStateException("Sandbox merchant ID cannot be used in production environment.");
            }
            validateApprovedDomain(APPROVED_PRODUCTION_DOMAIN, false);
        } else {
            validateApprovedDomain(APPROVED_SANDBOX_DOMAIN, true);
        }
    }

    private void validateApprovedDomain(String expectedDomain, boolean allowTestDomains) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            String host = uri.getHost();
            if (host == null) {
                return;
            }
            String hostLower = host.toLowerCase(Locale.ROOT);
            if (hostLower.equals(expectedDomain)) {
                return;
            }
            if (allowTestDomains) {
                if (ALLOWED_TEST_DOMAINS.contains(hostLower) || hostLower.endsWith(".test") || hostLower.endsWith(".example")) {
                    return;
                }
            }
            throw new IllegalStateException("Base URL '" + baseUrl + "' host '" + host
                    + "' is not an approved ABA PayWay domain for environment '" + environment + "'. Expected: " + expectedDomain);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid ABA PayWay base URL: " + baseUrl, e);
        }
    }

    public boolean isProduction() {
        return "production".equalsIgnoreCase(environment) || "prod".equalsIgnoreCase(environment);
    }

    public boolean isSandbox() {
        return !isProduction();
    }

    public String environment() {
        return environment;
    }

    public boolean livePaymentsEnabled() {
        return livePaymentsEnabled;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String createPath() {
        return createPath;
    }

    public String statusPath() {
        return statusPath;
    }

    public String purchasePath() {
        return purchasePath;
    }

    public String paymentOption() {
        return paymentOption;
    }

    public String qrImageTemplate() {
        return qrImageTemplate;
    }

    public int lifetimeMinutes() {
        return lifetimeMinutes;
    }

    public String merchantReference() {
        return merchantReference;
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

    public boolean usesOfficialCreateApi() {
        return OFFICIAL_CREATE_PATH.equals(normalized(createPath));
    }

    public boolean usesOfficialStatusApi() {
        return OFFICIAL_STATUS_PATH.equals(normalized(statusPath));
    }

    private String normalized(String path) {
        if (path == null) {
            return "";
        }
        String value = path.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Override
    public String toString() {
        return "AbaPayWayProperties{" +
                "environment='" + environment + '\'' +
                ", livePaymentsEnabled=" + livePaymentsEnabled +
                ", baseUrl='" + baseUrl + '\'' +
                ", merchantReference='" + merchantReference + '\'' +
                ", apiKey='[PROTECTED]'" +
                '}';
    }
}
