package org.example.gametopupkh.payment.bakong;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import kh.gov.nbc.bakong_khqr.BakongKHQR;
import kh.gov.nbc.bakong_khqr.model.CRCValidation;
import kh.gov.nbc.bakong_khqr.model.IndividualInfo;
import kh.gov.nbc.bakong_khqr.model.KHQRCurrency;
import kh.gov.nbc.bakong_khqr.model.KHQRData;
import kh.gov.nbc.bakong_khqr.model.KHQRDecodeData;
import kh.gov.nbc.bakong_khqr.model.KHQRResponse;
import kh.gov.nbc.bakong_khqr.model.KHQRStatus;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.payment.bakong.dto.BakongKhqrResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Generates dynamic KHQR codes with the official National Bank of Cambodia
 * {@code kh.gov.nbc.bakong_khqr:sdk-java} library, then decodes the result and
 * checks the account, amount, currency, expiry and checksum before returning it
 * to a customer. This fail-closed self-check protects the order flow from SDK
 * changes, floating-point conversion mistakes and configuration drift.
 */
@Service
public class BakongKhqrService {

    public static final String CURRENCY_USD_CODE = "840";
    public static final String CURRENCY_KHR_CODE = "116";
    public static final String DEFAULT_MCC = "5999"; // Miscellaneous Retail
    public static final String COUNTRY_CODE_KH = "KH";
    public static final int MAX_LIFETIME_MINUTES = 10;
    public static final String DYNAMIC_POINT_OF_INITIATION = "12";

    private final BakongProperties properties;

    public BakongKhqrService(BakongProperties properties) {
        this.properties = properties;
    }

    /**
     * Generates a dynamic KHQR string and its MD5 hash for the given order details.
     * The official SDK emits the EMVCo TLV payload, CRC-16 checksum and RFU
     * creation/expiry timestamps; this method independently verifies the result.
     *
     * @param amount           exact monetary amount
     * @param currency         ISO currency code ("USD" or "KHR")
     * @param paymentReference unique payment or order reference
     * @return payload containing KHQR string, MD5 hash, expiration, amount, and currency
     */
    public BakongKhqrResponse generateDynamicKhqr(BigDecimal amount, String currency, String paymentReference) {
        validateRequest(amount, currency, paymentReference);
        ensurePaymentCreationAvailable();

        String normalizedCurrency = currency.trim().toUpperCase(Locale.ROOT);
        KHQRCurrency sdkCurrency = resolveCurrency(normalizedCurrency);
        String expectedCurrencyCode = currencyCode(normalizedCurrency);

        String accountId = properties.accountId();

        String merchantName = sanitize(properties.merchantName(), 25, "GameTopUp-KH");
        String merchantCity = sanitize(properties.merchantCity(), 15, "Phnom Penh");
        String billNumber = sanitize(paymentReference.trim(), 25, paymentReference.trim());

        int lifetimeMinutes = Math.min(properties.lifetimeMinutes(), MAX_LIFETIME_MINUTES);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(lifetimeMinutes));

        IndividualInfo individualInfo = new IndividualInfo();
        individualInfo.setBakongAccountId(accountId.trim());
        individualInfo.setCurrency(sdkCurrency);
        individualInfo.setAmount(toSdkAmount(amount, normalizedCurrency));
        individualInfo.setMerchantName(merchantName);
        individualInfo.setMerchantCity(merchantCity);
        individualInfo.setMerchantCategoryCode(DEFAULT_MCC);
        individualInfo.setBillNumber(billNumber);
        individualInfo.setExpirationTimestamp(expiresAt.toEpochMilli());

        KHQRResponse<KHQRData> sdkResponse;
        try {
            sdkResponse = BakongKHQR.generateIndividual(individualInfo);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "KHQR_GENERATION_FAILED",
                    "The official KHQR SDK could not generate a code: " + e.getMessage(), e);
        }

        KHQRData data = requireSdkSuccess(sdkResponse, HttpStatus.BAD_REQUEST,
                "KHQR_GENERATION_FAILED", "generation");
        String finalKhqr = data.getQr();
        if (finalKhqr == null || finalKhqr.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "KHQR_SELF_CHECK_FAILED",
                    "The official KHQR SDK returned an empty QR payload");
        }

        validateIssuedQr(finalKhqr, amount, expectedCurrencyCode, accountId.trim(), billNumber, expiresAt);

        return new BakongKhqrResponse(
                finalKhqr,
                calculateMd5(finalKhqr),
                amount,
                normalizedCurrency,
                expiresAt,
                paymentReference
        );
    }

    /**
     * Fail-closed preflight used before any customer payment row is reserved.
     * A production deployment must explicitly enable live payments <em>and</em>
     * have an API token, because a QR that can never be verified would strand the
     * customer's money. Sandbox generation stays available without live approval
     * so the integration can be exercised with test data.
     */
    public void ensurePaymentCreationAvailable() {
        ensureLivePaymentsAllowed();
        if (properties.accountId() == null || properties.accountId().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BAKONG_ACCOUNT_ID_MISSING",
                    "A Bakong merchant account ID must be configured before generating a KHQR");
        }
    }

    private void ensureLivePaymentsAllowed() {
        if (!properties.isProduction()) {
            return;
        }
        if (!properties.isEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BAKONG_PAYMENT_DISABLED",
                    "Live Bakong payments are disabled by configuration");
        }
        if (properties.apiToken() == null || properties.apiToken().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BAKONG_TOKEN_MISSING",
                    "Live Bakong payments are enabled but BAKONG_TOKEN/BAKONG_API_TOKEN is not configured");
        }
    }

    private static void validateRequest(BigDecimal amount, String currency, String paymentReference) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Payment amount must be greater than zero");
        }
        if (currency == null || currency.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY", "Payment currency is required");
        }
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE", "Payment reference is required");
        }
    }

    /**
     * The SDK exposes amounts as {@link Double}. The database keeps money as
     * {@code NUMERIC(12,2)} and the caller passes a {@link BigDecimal}, so this
     * boundary is limited to values the SDK can represent exactly at two decimals.
     * The generated QR is decoded again before use to prove the amount survived.
     */
    private static Double toSdkAmount(BigDecimal amount, String currency) {
        BigDecimal normalized = amount.stripTrailingZeros();
        if ("KHR".equals(currency)) {
            if (normalized.scale() > 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                        "KHR Bakong payments must be whole riel because the KHQR specification does not allow KHR decimals");
            }
        } else if (normalized.scale() > 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Bakong USD payments support at most two decimal places");
        }
        return amount.doubleValue();
    }

    private static KHQRCurrency resolveCurrency(String currency) {
        return switch (currency) {
            case "USD" -> KHQRCurrency.USD;
            case "KHR" -> KHQRCurrency.KHR;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENCY_NOT_SUPPORTED",
                    "Bakong KHQR only supports USD and KHR, but received " + currency);
        };
    }

    private static String currencyCode(String currency) {
        return switch (currency) {
            case "USD" -> CURRENCY_USD_CODE;
            case "KHR" -> CURRENCY_KHR_CODE;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENCY_NOT_SUPPORTED",
                    "Bakong KHQR only supports USD and KHR, but received " + currency);
        };
    }

    private static void validateIssuedQr(String qr, BigDecimal amount, String expectedCurrencyCode,
            String expectedAccountId, String expectedBillNumber, Instant expectedExpiresAt) {
        KHQRResponse<CRCValidation> verifyResponse;
        try {
            verifyResponse = BakongKHQR.verify(qr);
        } catch (RuntimeException e) {
            throw selfCheckFailure("The official KHQR SDK could not verify the generated code", e);
        }
        if (verifyResponse == null || verifyResponse.getData() == null || !verifyResponse.getData().isValid()) {
            throw selfCheckFailure("The generated KHQR has an invalid CRC-16 checksum", null);
        }

        KHQRResponse<KHQRDecodeData> decodeResponse;
        try {
            decodeResponse = BakongKHQR.decode(qr);
        } catch (RuntimeException e) {
            throw selfCheckFailure("The official KHQR SDK could not decode the generated code", e);
        }
        KHQRDecodeData decoded = requireSdkSuccess(decodeResponse, HttpStatus.INTERNAL_SERVER_ERROR,
                "KHQR_SELF_CHECK_FAILED", "decoding");

        if (!DYNAMIC_POINT_OF_INITIATION.equals(decoded.getPointOfInitiationMethod())) {
            throw selfCheckFailure("The generated KHQR is not a dynamic code", null);
        }
        if (!expectedAccountId.equalsIgnoreCase(decoded.getBakongAccountID())) {
            throw selfCheckFailure("The generated KHQR contains a different receiving account", null);
        }
        if (decoded.getTransactionAmount() == null
                || new BigDecimal(decoded.getTransactionAmount()).compareTo(amount) != 0) {
            throw selfCheckFailure("The generated KHQR contains a different amount", null);
        }
        if (!expectedCurrencyCode.equals(decoded.getTransactionCurrency())) {
            throw selfCheckFailure("The generated KHQR contains a different currency", null);
        }
        if (!expectedBillNumber.equals(decoded.getBillNumber())) {
            throw selfCheckFailure("The generated KHQR contains a different bill number", null);
        }
        if (!String.valueOf(expectedExpiresAt.toEpochMilli()).equals(decoded.getExpirationTimestamp())) {
            throw selfCheckFailure("The generated KHQR contains a different expiration timestamp", null);
        }
    }

    private static <T> T requireSdkSuccess(KHQRResponse<T> response, HttpStatus status,
            String errorCode, String operation) {
        KHQRStatus khqrStatus = response == null ? null : response.getKHQRStatus();
        if (response == null || khqrStatus == null || khqrStatus.getCode() != 0 || response.getData() == null) {
            Integer sdkErrorCode = khqrStatus == null ? null : khqrStatus.getErrorCode();
            String sdkMessage = khqrStatus == null ? "no status returned" : khqrStatus.getMessage();
            throw new ApiException(status, errorCode,
                    "Official KHQR SDK " + operation + " failed"
                            + (sdkErrorCode == null ? "" : " (error " + sdkErrorCode + ")")
                            + ": " + sdkMessage);
        }
        return response.getData();
    }

    private static ApiException selfCheckFailure(String message, Exception cause) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "KHQR_SELF_CHECK_FAILED",
                message + "; refusing to return the QR", cause);
    }

    private static String sanitize(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    /**
     * Calculates the MD5 hash (32 lowercase hex characters) of the complete KHQR
     * UTF-8 string. Bakong's {@code check_transaction_by_md5} endpoint expects this
     * value, and it is stored per payment for later verification.
     */
    public static String calculateMd5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 MessageDigest not available", e);
        }
    }
}
