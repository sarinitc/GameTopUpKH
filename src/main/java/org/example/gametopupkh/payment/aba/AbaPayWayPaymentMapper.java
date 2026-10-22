package org.example.gametopupkh.payment.aba;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.payment.aba.dto.AbaCheckTransactionResponse;
import org.example.gametopupkh.payment.aba.dto.AbaQrResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Maps official ABA PayWay QR responses and legacy adapter aliases into a
 * provider-neutral payment intent.
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "aba")
public class AbaPayWayPaymentMapper {

    public PaymentIntent toIntent(AbaQrResponse response, PaymentRequest request) {
        return toIntent(response.payload(), request, request == null ? null : request.transactionId());
    }

    public PaymentIntent toIntent(AbaCheckTransactionResponse response) {
        return toIntent(response.payload(), null, null);
    }

    public PaymentIntent toIntent(AbaCheckTransactionResponse response, String fallbackReference) {
        return toIntent(response.payload(), null, fallbackReference);
    }

    private PaymentIntent toIntent(JsonNode response, PaymentRequest request, String fallbackReference) {
        if (response == null || response.isNull()) {
            throw new PaymentGatewayException(AbaPayWayGateway.NAME, "Empty response from AbaPayWay");
        }
        requireSuccessfulCall(response);

        String reference = text(response, "tran_id", "transaction_id", "reference", "id");
        if (reference == null) {
            reference = nestedText(response, "status", "tran_id");
        }
        if (reference == null && (isObject(response.get("status")) || isObject(response.get("data")))) {
            reference = fallbackReference;
        }
        if (reference == null || reference.isBlank()) {
            throw new PaymentGatewayException(AbaPayWayGateway.NAME,
                    "AbaPayWay response did not contain a transaction reference");
        }

        BigDecimal amount = request != null ? request.amount()
                : decimal(response, "total_amount", "payment_amount", "amount");
        String currency = request != null ? request.currency()
                : text(response, "payment_currency", "currency");
        return new PaymentIntent(
                AbaPayWayGateway.NAME,
                reference,
                mapProviderStatus(response),
                amount,
                currency,
                text(response, "redirect_url", "payment_url", "qr_url", "abapay_deeplink", "checkout_qr_url"),
                Instant.now(),
                text(response, "qrString", "qr_string", "qr_code", "qr", "qrCode"),
                text(response, "qrImage", "qr_image", "qr_image_url", "qr_code_url"),
                instant(response, "expires_at", "expiry", "valid_until"));
    }

    private void requireSuccessfulCall(JsonNode response) {
        JsonNode status = response.get("status");
        if (status == null || !status.isObject()) {
            return;
        }
        String code = textValue(status.get("code"));
        if (code == null || code.isBlank() || "0".equals(code) || "00".equals(code)) {
            return;
        }
        String message = textValue(status.get("message"));
        throw new PaymentGatewayException(AbaPayWayGateway.NAME,
                "AbaPayWay request failed (" + code + ")"
                        + (message == null || message.isBlank() ? "" : ": " + message));
    }

    private PaymentStatus mapProviderStatus(JsonNode response) {
        String paymentStatus = text(response, "payment_status", "paymentStatus");
        if (paymentStatus != null) {
            return mapStatus(paymentStatus);
        }
        Integer statusCode = integer(response, "payment_status_code");
        if (statusCode != null) {
            return mapStatusCode(statusCode);
        }
        return mapStatus(text(response, "status", "state"));
    }

    private PaymentStatus mapStatusCode(int statusCode) {
        return switch (statusCode) {
            case 0 -> PaymentStatus.COMPLETED;
            case 2 -> PaymentStatus.PENDING;
            case 3 -> PaymentStatus.FAILED;
            case 4 -> PaymentStatus.NEEDS_REVIEW;
            case 7 -> PaymentStatus.CANCELLED;
            default -> PaymentStatus.PENDING;
        };
    }

    private PaymentStatus mapStatus(String status) {
        if (status == null) {
            return PaymentStatus.PENDING;
        }
        return switch (status.toUpperCase()) {
            case "APPROVED", "PRE-AUTH", "PAID", "COMPLETED", "SUCCESS" -> PaymentStatus.COMPLETED;
            case "FAILED", "ERROR", "DECLINED" -> PaymentStatus.FAILED;
            case "EXPIRED" -> PaymentStatus.EXPIRED;
            case "CANCELLED", "VOIDED" -> PaymentStatus.CANCELLED;
            case "REFUNDED", "REVIEW", "NEEDS_REVIEW" -> PaymentStatus.NEEDS_REVIEW;
            default -> PaymentStatus.PENDING;
        };
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = textValue(field(node, field));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String nestedText(JsonNode node, String parent, String field) {
        JsonNode parentNode = node.get(parent);
        if (parentNode == null || !parentNode.isObject()) {
            return null;
        }
        String value = textValue(parentNode.get(field));
        return value == null || value.isBlank() ? null : value;
    }

    private boolean isObject(JsonNode value) {
        return value != null && value.isObject();
    }

    private String textValue(JsonNode value) {
        if (value == null || value.isNull() || value.isObject() || value.isArray()) {
            return null;
        }
        String text = value.asText();
        return text == null ? null : text.trim();
    }

    private JsonNode field(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            JsonNode data = node.get("data");
            if (data != null && data.isObject()) {
                value = data.get(field);
            }
        }
        return value;
    }

    private BigDecimal decimal(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = field(node, field);
            if (value != null && !value.isNull()) {
                try {
                    return value.decimalValue();
                } catch (RuntimeException ignored) {
                    // Ignore a malformed optional amount and try the next alias.
                }
            }
        }
        return null;
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = field(node, field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return value.isNumber() ? value.asInt() : Integer.valueOf(value.asText().trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Instant instant(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = field(node, field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.canConvertToLong()) {
                long raw = value.asLong();
                return raw > 10_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
            }
            String text = value.asText();
            if (text == null || text.isBlank()) {
                continue;
            }
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                // The legacy adapter does not define a single timestamp format.
            }
        }
        return null;
    }
}
