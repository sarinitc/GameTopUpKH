package org.example.gametopupkh.catalog.packagecatalog.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Payload accepted by the package write endpoints. The same shape serves create,
 * update and status changes; the validation group applied by the controller decides
 * which fields are required for the operation.
 */
public record GamePackageRequest(

        @NotNull(message = "gameId is required", groups = Create.class)
        UUID gameId,

        @NotBlank(message = "name is required", groups = {Create.class, Update.class})
        @Size(max = 150, message = "name must be at most 150 characters")
        String name,

        @NotBlank(message = "sku is required", groups = Create.class)
        @Size(max = 100, message = "sku must be at most 100 characters")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                message = "sku must contain letters, digits, dashes and underscores")
        String sku,

        @NotNull(message = "diamondAmount is required", groups = {Create.class, Update.class})
        @Positive(message = "diamondAmount must be greater than 0")
        Integer diamondAmount,

        @NotNull(message = "price is required", groups = {Create.class, Update.class})
        @DecimalMin(value = "0.01", message = "price must be greater than 0")
        @Digits(integer = 10, fraction = 2, message = "price must have at most 10 integer and 2 fraction digits")
        BigDecimal price,

        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3 letter ISO code")
        String currency,

        @NotNull(message = "active is required", groups = Status.class)
        Boolean active) {

    public interface Create extends jakarta.validation.groups.Default {
    }

    public interface Update extends jakarta.validation.groups.Default {
    }

    public interface Status extends jakarta.validation.groups.Default {
    }
}
