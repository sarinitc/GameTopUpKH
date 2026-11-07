package org.example.gametopupkh.payment.bakong.dto;

/**
 * Result of validating the configured Bakong merchant account with
 * {@code /v1/check_bakong_account}. A non-zero provider response code means the
 * account could not be verified and must be investigated before enabling payments.
 */
public record BakongAccountVerificationResponse(
        String accountId,
        boolean verified,
        Integer responseCode,
        String responseMessage) {
}
