package org.example.gametopupkh.payment.bakong.dto;

/**
 * Response payload from the Bakong Open API {@code /v1/check_bakong_account} lookup.
 * Callers should rely on {@code responseCode == 0} for existence. The {@code data}
 * object carries eligibility signals (accountStatus, frozen, kycStatus, canReceive)
 * that staff should review before enabling live payments; the provider does not
 * document a stable schema, so any component may be absent.
 */
public record BakongCheckAccountResponse(
        Integer responseCode,
        String responseMessage,
        BakongAccountData data) {

    public boolean isVerified() {
        return responseCode != null && responseCode == 0;
    }

    /**
     * Eligibility details returned by the live API for an existing account.
     * Extra or renamed provider fields are ignored by the JSON binder.
     */
    public record BakongAccountData(
            String accountStatus,
            Integer frozen,
            String kycStatus,
            Boolean canReceive) {
    }
}
