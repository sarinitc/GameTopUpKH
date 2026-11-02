package org.example.gametopupkh.payment.bakong;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.payment.bakong.dto.BakongKhqrResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Generates dynamic KHQR conforming to EMVCo Merchant-Presented Mode (MPM)
 * and the National Bank of Cambodia (NBC) Bakong standard.
 */
@Service
public class BakongKhqrService {

    public static final String CURRENCY_USD_CODE = "840";
    public static final String CURRENCY_KHR_CODE = "116";
    public static final String DEFAULT_MCC = "5999"; // Miscellaneous Retail
    public static final String COUNTRY_CODE_KH = "KH";
    public static final int MAX_LIFETIME_MINUTES = 10;

    private final BakongProperties properties;

    public BakongKhqrService(BakongProperties properties) {
        this.properties = properties;
    }

    /**
     * Generates a dynamic KHQR string and its MD5 hash for the given order details.
     *
     * @param amount           exact monetary amount
     * @param currency         ISO currency code ("USD" or "KHR")
     * @param paymentReference unique payment or order reference
     * @return payload containing KHQR string, MD5 hash, expiration, amount, and currency
     */
    public BakongKhqrResponse generateDynamicKhqr(BigDecimal amount, String currency, String paymentReference) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Payment amount must be greater than zero");
        }
        if (currency == null || currency.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY", "Payment currency is required");
        }
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE", "Payment reference is required");
        }

        String normalizedCurrency = currency.trim().toUpperCase(Locale.ROOT);
        String currencyCode = resolveCurrencyCode(normalizedCurrency);
        String formattedAmount = formatAmount(amount, normalizedCurrency);

        String accountId = properties.hasAccountId() ? properties.accountId() : "gametopup@bakong";
        String merchantName = sanitize(properties.merchantName(), 25, "GameTopUp-KH");
        String merchantCity = sanitize(properties.merchantCity(), 15, "Phnom Penh");
        String billNumber = sanitize(paymentReference, 25, paymentReference);

        StringBuilder payload = new StringBuilder();

        // 00: Payload Format Indicator (Version 01)
        appendTlv(payload, "00", "01");

        // 01: Point of Initiation Method (12 = Dynamic)
        appendTlv(payload, "01", "12");

        // 29: Merchant Account Information (Individual / Solo Merchant Account)
        // Sub-tag 00: Bakong Account ID
        StringBuilder tag29Value = new StringBuilder();
        appendTlv(tag29Value, "00", accountId);
        appendTlv(payload, "29", tag29Value.toString());

        // 52: Merchant Category Code
        appendTlv(payload, "52", DEFAULT_MCC);

        // 53: Transaction Currency (840 = USD, 116 = KHR)
        appendTlv(payload, "53", currencyCode);

        // 54: Transaction Amount
        appendTlv(payload, "54", formattedAmount);

        // 58: Country Code (KH)
        appendTlv(payload, "58", COUNTRY_CODE_KH);

        // 59: Merchant Name
        appendTlv(payload, "59", merchantName);

        // 60: Merchant City
        appendTlv(payload, "60", merchantCity);

        // 62: Additional Data Field Template (Bill Number / Reference)
        StringBuilder tag62Value = new StringBuilder();
        appendTlv(tag62Value, "01", billNumber);
        appendTlv(payload, "62", tag62Value.toString());

        // 63: CRC-16 Checksum (Container tag 63, length 04)
        payload.append("6304");
        String crc = calculateCrc16(payload.toString());
        payload.append(crc);

        String finalKhqr = payload.toString();
        String md5 = calculateMd5(finalKhqr);

        int lifetimeMinutes = Math.min(properties.lifetimeMinutes(), MAX_LIFETIME_MINUTES);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(lifetimeMinutes));

        return new BakongKhqrResponse(
                finalKhqr,
                md5,
                amount,
                normalizedCurrency,
                expiresAt,
                paymentReference
        );
    }

    private static String resolveCurrencyCode(String currency) {
        return switch (currency) {
            case "USD" -> CURRENCY_USD_CODE;
            case "KHR" -> CURRENCY_KHR_CODE;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENCY_NOT_SUPPORTED",
                    "Bakong KHQR only supports USD and KHR, but received " + currency);
        };
    }

    private static String formatAmount(BigDecimal amount, String currency) {
        if ("KHR".equalsIgnoreCase(currency)) {
            // KHR is typically an integer or 2 decimal places
            return amount.stripTrailingZeros().scale() <= 0
                    ? amount.setScale(0, RoundingMode.HALF_UP).toPlainString()
                    : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static void appendTlv(StringBuilder sb, String tag, String value) {
        sb.append(tag);
        sb.append(String.format("%02d", value.length()));
        sb.append(value);
    }

    private static String sanitize(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    /**
     * Calculates the CRC16-CCITT/FALSE checksum over the given payload.
     * Polynomial: 0x1021, Seed: 0xFFFF.
     */
    public static String calculateCrc16(String input) {
        int crc = 0xFFFF;
        int polynomial = 0x1021;
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            crc ^= ((b & 0xFF) << 8);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ polynomial) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return String.format("%04X", crc);
    }

    /**
     * Calculates the MD5 hash (32 lowercase hex characters) of the given string.
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
