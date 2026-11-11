package de.alpdesk.dto.tenant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Serdeable
@Schema(description = "Request object for creating or changing an API key")
public record ApiKeyRequest(
        @Schema(description = "Label to tell keys apart; on a change left out = unchanged", example = "ERP Produktion")
        @Nullable String name,
        @Schema(description = "Whether the key's chat questions are kept as conversations; left out on create = true, on a change = unchanged")
        @Nullable Boolean keepsHistory,
        @Schema(description = "Whether the key's chat questions may search the web; left out on create = false, on a change = unchanged")
        @Nullable Boolean webSearch,
        @Schema(description = "The knowledge areas the key finds and pushes into besides the company knowledge; left out on create = none, on a change = unchanged")
        @Nullable List<Long> areaIds,
        @Schema(description = "The scheduled tasks the key may start (/api/v1/tasks); left out on create = none, on a change = unchanged")
        @Nullable List<Long> taskIds,
        @Schema(description = "The connected software its chat questions may use; left out on create = none, on a change = unchanged")
        @Nullable List<Long> connectionIds
) {
}
