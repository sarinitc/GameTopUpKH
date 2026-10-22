package org.example.gametopupkh.payment.aba;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Small, dependency-free HMAC helper used when an ABA callback secret is configured.
 * The callback is still verified against the provider status API before a payment is
 * treated as completed.
 */
public final class AbaPayWayHashService {

    private static final String ALGORITHM = "HmacSHA512";

    private AbaPayWayHashService() {
    }

    public static String hmacSha512Base64(String secret, String payload) {
        return Base64.getEncoder().encodeToString(hmacSha512(secret, payload));
    }

    public static String hmacSha512Hex(String secret, String payload) {
        return HexFormat.of().formatHex(hmacSha512(secret, payload));
    }

    public static boolean matches(String secret, String payload, String signature) {
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected = hmacSha512(secret, payload);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature.trim());
        } catch (IllegalArgumentException e) {
            try {
                provided = Base64.getDecoder().decode(signature.trim());
            } catch (IllegalArgumentException invalidEncoding) {
                return false;
            }
        }
        return MessageDigest.isEqual(expected, provided);
    }

    private static byte[] hmacSha512(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not calculate the callback signature", e);
        }
    }
}
