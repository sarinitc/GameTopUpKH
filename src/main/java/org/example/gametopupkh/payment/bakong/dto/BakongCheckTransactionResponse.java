package org.example.gametopupkh.payment.bakong.dto;

import java.math.BigDecimal;

/**
 * Response payload from Bakong Open API {@code /v1/check_transaction_by_md5}.
 */
public record BakongCheckTransactionResponse(
        Integer responseCode,
        String responseMessage,
        BakongTransactionData data) {

    public boolean isSuccess() {
        return responseCode != null && responseCode == 0 && data != null;
    }

    public record BakongTransactionData(
            String hash,
            String fromAccountId,
            String toAccountId,
            String currency,
            BigDecimal amount,
            String description,
            Long createdDateMs,
            Long acknowledgedDateMs,
            String externalRef) {
    }
}
