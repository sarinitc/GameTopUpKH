package org.example.gametopupkh.payment.aba;

import java.util.List;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.payment.aba.dto.AbaCallbackRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Verifies the optional callback HMAC. Even when a signature is accepted, the
 * application service still reads the authoritative payment state from the provider;
 * a callback can trigger a check but can never mark an order paid by itself.
 */
@Component
public class AbaPayWayCallbackVerifier {

    private final String callbackSecret;

    public AbaPayWayCallbackVerifier(@Value("${payment.aba.callback-secret:}") String callbackSecret) {
        this.callbackSecret = callbackSecret;
    }

    public void verify(AbaCallbackRequest callback) {
        if (callbackSecret == null || callbackSecret.isBlank()) {
            return;
        }
        if (callback.hash() == null || callback.hash().isBlank()) {
            throw invalidSignature();
        }
        List<String> candidates = List.of(
                value(callback.reference()) + value(callback.status())
                        + plain(callback.amount()) + value(callback.currency()),
                value(callback.reference()) + "|" + value(callback.status())
                        + "|" + plain(callback.amount()) + "|" + value(callback.currency()),
                value(callback.reference()) + "|" + plain(callback.amount())
                        + "|" + value(callback.status()) + "|" + value(callback.currency()));
        boolean valid = candidates.stream()
                .anyMatch(payload -> AbaPayWayHashService.matches(callbackSecret, payload, callback.hash()));
        if (!valid) {
            throw invalidSignature();
        }
    }

    private ApiException invalidSignature() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_PAYMENT_CALLBACK_SIGNATURE",
                "The payment callback signature is not valid");
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private String plain(java.math.BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
