package de.alpdesk.dto.api.v1;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

@Serdeable
@Schema(description = "A run that was started - it runs in the background; ask for it with its id")
public record ApiTaskRunStarted(Long runId) {
}
