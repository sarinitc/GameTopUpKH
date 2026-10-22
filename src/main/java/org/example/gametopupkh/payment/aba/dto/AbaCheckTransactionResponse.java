package org.example.gametopupkh.payment.aba.dto;

import tools.jackson.databind.JsonNode;

/** Raw legacy lookup response, preserving unknown fields and mapping aliases. */
public record AbaCheckTransactionResponse(JsonNode payload) {
}
