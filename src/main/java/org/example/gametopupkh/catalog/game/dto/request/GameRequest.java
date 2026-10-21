package org.example.gametopupkh.catalog.game.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload accepted by the game write endpoints. The same shape serves create, update
 * and status changes; the validation group applied by the controller decides which
 * fields are required for the operation.
 */
public record GameRequest(

        @NotBlank(message = "name is required", groups = {Create.class, Update.class})
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @NotBlank(message = "slug is required", groups = Create.class)
        @Size(max = 120, message = "slug must be at most 120 characters")
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "slug must contain lowercase letters, digits and single dashes")
        String slug,

        @Size(max = 100, message = "playerIdLabel must be at most 100 characters")
        String playerIdLabel,

        Boolean requiresZoneId,

        @NotNull(message = "active is required", groups = Status.class)
        Boolean active) {

    public interface Create {
    }

    public interface Update {
    }

    public interface Status {
    }
}
