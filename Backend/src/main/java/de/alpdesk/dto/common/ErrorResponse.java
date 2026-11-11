package de.alpdesk.dto.common;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Serdeable
@Schema(description = "Error response body")
public record ErrorResponse(
        @Schema(description = "Human-readable error message in English",
                example = "The request contains invalid or incomplete data.", requiredMode = Schema.RequiredMode.REQUIRED)
        String message,
        @Schema(description = "Stable identifier of the error, <area>.<rule> - a client may switch on it; it does not "
                + "change when the message's wording does", example = "db.invalidData",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String code,
        @Schema(description = "The values inserted into the message, by name (empty when there are none)",
                example = "{\"username\": \"anna\"}", requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, String> params
) {

    public ErrorResponse {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** An error without values in its message. */
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(message, code, Map.of());
    }
}
