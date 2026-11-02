package org.example.gametopupkh.payment.bakong;

import java.math.BigDecimal;
import java.time.Instant;

import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse.BakongTransactionData;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.springframework.stereotype.Component;

/**
 * Maps typed Bakong Open API responses to domain payment intents.
 */
@Component
public class BakongPaymentMapper {

    public static final String GATEWAY_NAME = "bakong";

    /**
     * Maps a check transaction response into a PaymentIntent.
     *
     * @param response           response from Bakong API
     * @param fallbackReference fallback reference (e.g. order number or payment reference)
     * @param expectedAmount     expected amount for validation
     * @param expectedCurrency   expected currency
     * @return PaymentIntent representing the transaction status
     */
    public PaymentIntent toIntent(
            BakongCheckTransactionResponse response,
            String fallbackReference,
            BigDecimal expectedAmount,
            String expectedCurrency) {
        if (response == null) {
            return new PaymentIntent(
                    GATEWAY_NAME,
                    fallbackReference,
                    PaymentStatus.PENDING,
                    expectedAmount,
                    expectedCurrency,
                    null,
                    Instant.now()
            );
        }

        if (response.isSuccess()) {
            BakongTransactionData data = response.data();
            String hash = data.hash() != null && !data.hash().isBlank() ? data.hash() : fallbackReference;
            BigDecimal amount = data.amount() != null ? data.amount() : expectedAmount;
            String currency = data.currency() != null ? data.currency() : expectedCurrency;
            Instant createdAt = data.createdDateMs() != null
                    ? Instant.ofEpochMilli(data.createdDateMs())
                    : Instant.now();

            return new PaymentIntent(
                    GATEWAY_NAME,
                    hash,
                    PaymentStatus.COMPLETED,
                    amount,
                    currency,
                    null,
                    createdAt
            );
        }

        // Response code is non-zero (transaction not found yet or pending)
        return new PaymentIntent(
                GATEWAY_NAME,
                fallbackReference,
                PaymentStatus.PENDING,
                expectedAmount,
                expectedCurrency,
                null,
                Instant.now()
        );
    }
}
