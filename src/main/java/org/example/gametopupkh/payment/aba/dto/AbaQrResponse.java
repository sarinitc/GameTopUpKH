package org.example.gametopupkh.payment.aba.dto;

import tools.jackson.databind.JsonNode;

/**
 * Raw legacy create response, retaining all provider fields and aliases for the
 * mapper. This is not a verified ABA PayWay QR response schema.
 */
public record AbaQrResponse(JsonNode payload) {
}
