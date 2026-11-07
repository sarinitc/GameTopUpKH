package org.example.gametopupkh.payment.bakong.dto;

/**
 * Response payload from the Bakong Open API {@code /v1/check_bakong_account} lookup.
 * The payload is deliberately kept opaque because Bakong does not document a stable
 * shape for {@code data}; callers should rely on {@code responseCode == 0}.
 */
public record BakongCheckAccountResponse(
        Integer responseCode,
        String responseMessage,
        Object data) {

    public boolean isVerified() {
        return responseCode != null && responseCode == 0;
    }
}
