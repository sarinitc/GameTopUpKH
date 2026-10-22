package org.example.gametopupkh.payment.aba.dto;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import tools.jackson.databind.JsonNode;

/**
 * Normalized view of a PayWay callback. The adapter deliberately accepts several
 * legacy aliases because the provider payload has changed over time; the callback
 * is only used to trigger an authoritative status lookup.
 */
public record AbaCallbackRequest(
        String reference,
        String eventKey,
        String status,
        BigDecimal amount,
        String currency,
        String hash) {

    public static AbaCallbackRequest from(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return new AbaCallbackRequest(null, null, null, null, null, null);
        }
        String reference = text(payload, "transaction_id", "transactionId", "tran_id", "tranId",
                "reference", "payment_id", "paymentId", "order_id", "orderId");
        String status = text(payload, "status", "payment_status", "paymentStatus", "state");
        BigDecimal amount = decimal(payload, "amount", "total_amount", "totalAmount");
        String currency = text(payload, "currency", "currency_code", "currencyCode");
        String hash = text(payload, "hash", "signature", "hmac");
        String eventKey = text(payload, "event_id", "eventId", "callback_id", "callbackId");
        if (eventKey == null && hash != null) {
            eventKey = hash;
        }
        if (eventKey == null && reference != null) {
            eventKey = fallbackEventKey(reference, status, amount, currency);
        }
        return new AbaCallbackRequest(reference, eventKey, status, amount, currency, hash);
    }

    private static String fallbackEventKey(String reference, String status, BigDecimal amount, String currency) {
        String canonical = reference + "|" + value(status) + "|" + value(amount) + "|" + value(currency);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return canonical;
        }
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = field(node, field);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = field(node, field);
            if (value != null && !value.isNull()) {
                try {
                    return value.decimalValue();
                } catch (RuntimeException ignored) {
                    // Ignore a malformed optional callback field and try the next alias.
                }
            }
        }
        return null;
    }

    private static JsonNode field(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            JsonNode data = node.get("data");
            if (data != null && data.isObject()) {
                value = data.get(field);
            }
        }
        return value;
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
