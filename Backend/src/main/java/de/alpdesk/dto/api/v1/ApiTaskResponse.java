package de.alpdesk.dto.api.v1;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Serdeable
@Schema(description = "A scheduled task the API key may start")
public record ApiTaskResponse(
        Long id,
        @Schema(example = "Log check interfaces")
        String name,
        @Schema(description = "What the task is for, as its text says; null: none written",
                example = "Checks the interfaces' logs every five hours")
        @Nullable String description,
        @Schema(description = "False: switched off - it does not start")
        boolean active,
        @Schema(description = "The state of its last run; null: it never ran")
        @Nullable String lastRunState,
        @Nullable Instant lastRunAt
) {
}
