package org.example.gametopupkh.order;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload of the order creation endpoint. The price is never taken from the client,
 * it is read from the catalog when the order is created.
 */
public record OrderRequest(

        @NotNull(message = "packageId is required")
        UUID packageId,

        @NotBlank(message = "playerId is required")
        @Size(max = 100, message = "playerId must be at most 100 characters")
        String playerId,

        @Size(max = 50, message = "zoneId must be at most 50 characters")
        String zoneId,

        @Email(message = "contactEmail must be a valid address")
        @Size(max = 255, message = "contactEmail must be at most 255 characters")
        String contactEmail,

        @Pattern(regexp = "^\\+[1-9][0-9]{1,14}$",
                message = "contactPhone must use the international format, for example +85512345678")
        @Size(max = 20, message = "contactPhone must be at most 20 characters")
        String contactPhone) {
}
