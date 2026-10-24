package org.example.gametopupkh.supplier.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import org.example.gametopupkh.supplier.enums.SupplierStatus;

public record SupplierRequest(
        @NotBlank(groups = {Create.class, Update.class})
        @Size(max = 150)
        String name,
        @NotNull(groups = Status.class)
        SupplierStatus status) {
    public SupplierRequest {
        name = name == null ? null : name.trim();
    }

    public interface Create extends Default { }
    public interface Update extends Default { }
    public interface Status extends Default { }
}
