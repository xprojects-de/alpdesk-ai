package de.alpdesk.dto.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A task to save: its text (YAML or JSON) and whether it may run. Which keys may start it is chosen at the keys (the
 * API keys' and MCP keys' taskIds).
 *
 * @param active left out on create = true, on update = unchanged
 */
@Serdeable
public record TaskRequest(@Nullable String source, @Nullable Boolean active) {
}
