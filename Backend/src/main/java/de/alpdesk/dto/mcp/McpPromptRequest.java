package de.alpdesk.dto.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** A prompt's switch; left out: unchanged. */
@Serdeable
public record McpPromptRequest(@Nullable Boolean enabled) {
}
