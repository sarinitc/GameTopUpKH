package org.example.gametopupkh.refund;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * Staff request to refund a completed payment. The amount defaults to the full
 * payment amount when omitted.
 */
public record RefundRequest(
        @DecimalMin(value = "0.01", message = "must be greater than zero")
        BigDecimal amount,
        @Size(max = 500, message = "must be at most 500 characters")
        String reason) {
}
