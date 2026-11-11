package de.alpdesk.dto.tenant;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

@Serdeable
@Schema(description = "A newly created API key - the only response that ever contains the full key")
public record ApiKeyCreatedResponse(
        ApiKeyResponse key,
        @Schema(description = "The full key. It is not stored and cannot be retrieved again.")
        String plainKey
) {
}
