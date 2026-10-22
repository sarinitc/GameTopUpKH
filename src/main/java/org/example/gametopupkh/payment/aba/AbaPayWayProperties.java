package org.example.gametopupkh.payment.aba;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ABA PayWay configuration. The defaults point at the official QR API; explicitly
 * configured non-official paths retain the legacy configurable adapter behavior.
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

    private final String baseUrl;
    private final String apiKey;
    private final String createPath;
    private final String statusPath;
    private final String purchasePath;
    private final String paymentOption;
    private final String qrImageTemplate;
    private final int lifetimeMinutes;
    private final String merchantReference;

    @Autowired
    public AbaPayWayProperties(
            @Value("${payment.aba.base-url:https://checkout-sandbox.payway.com.kh}") String baseUrl,
            @Value("${payment.aba.api-key:}") String apiKey,
            @Value("${payment.aba.create-path:" + OFFICIAL_CREATE_PATH + "}") String createPath,
            @Value("${payment.aba.status-path:" + OFFICIAL_STATUS_PATH + "}") String statusPath,
            @Value("${payment.aba.purchase-path:" + OFFICIAL_PURCHASE_PATH + "}") String purchasePath,
            @Value("${payment.aba.payment-option:abapay_khqr}") String paymentOption,
            @Value("${payment.aba.qr-image-template:template3_color}") String qrImageTemplate,
            @Value("${payment.aba.lifetime-minutes:30}") int lifetimeMinutes,
            @Value("${payment.aba.merchant-id:}") String merchantReference) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.createPath = createPath;
        this.statusPath = statusPath;
        this.purchasePath = purchasePath;
        this.paymentOption = paymentOption;
        this.qrImageTemplate = qrImageTemplate;
        this.lifetimeMinutes = lifetimeMinutes;
        this.merchantReference = merchantReference;
    }

    public AbaPayWayProperties(String baseUrl, String apiKey, String createPath, String statusPath,
            String merchantReference) {
        this(baseUrl, apiKey, createPath, statusPath, OFFICIAL_PURCHASE_PATH, "abapay_khqr",
                "template3_color", 30, merchantReference);
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
}
