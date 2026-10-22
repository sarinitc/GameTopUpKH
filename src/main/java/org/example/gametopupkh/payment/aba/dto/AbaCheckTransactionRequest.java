package org.example.gametopupkh.payment.aba.dto;

/**
 * Lookup input for the legacy configurable adapter. The reference is substituted
 * into the configured GET path; no JSON request body is sent.
 */
public record AbaCheckTransactionRequest(String gatewayReference) {
}
