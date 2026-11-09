package org.example.gametopupkh.payment.bakong.dto;

/**
 * Result of validating the configured Bakong merchant account with
 * {@code /v1/check_bakong_account}. A non-zero provider response code means the
 * account could not be verified and must be investigated before enabling payments.
 *
 * <p>{@code verified} reports account existence only ({@code responseCode == 0}).
 * Staff should additionally review the eligibility fields supplied by live:
 * {@code canReceive} should be {@code true}, {@code frozen} should be {@code 0},
 * {@code kycStatus} should be {@code FULL_KYC}, and {@code accountStatus} should be
 * {@code ACTIVATED}. Any of them being absent or adverse means live payments must
 * stay disabled.</p>
 */
public record BakongAccountVerificationResponse(
        String accountId,
        boolean verified,
        Integer responseCode,
        String responseMessage,
        String accountStatus,
        Integer frozen,
        String kycStatus,
        Boolean canReceive) {
}
