package org.example.gametopupkh.payment.bakong.dto;

/**
 * Request payload for the Bakong Open API {@code /v1/check_bakong_account} lookup.
 * The official contract takes the Bakong account ID (for example {@code merchant@bank}).
 */
public record BakongCheckAccountRequest(String accountId) {
}
