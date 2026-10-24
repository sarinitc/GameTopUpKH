package org.example.gametopupkh.supplier.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;

public record SupplierProductRequest(
        @NotNull(groups = Create.class) UUID supplierId,
        @NotNull(groups = Create.class) UUID packageId,
        @NotBlank(groups = {Create.class, Update.class}) @Size(max = 150) String externalSku,
        @NotNull(groups = {Create.class, Update.class}) @DecimalMin("0")
        @Digits(integer = 10, fraction = 2) BigDecimal costPrice,
        @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotNull(groups = Status.class) Boolean active) {
    public SupplierProductRequest {
        externalSku = externalSku == null ? null : externalSku.trim();
    }

    public interface Create extends Default { }
    public interface Update extends Default { }
    public interface Status extends Default { }
}
