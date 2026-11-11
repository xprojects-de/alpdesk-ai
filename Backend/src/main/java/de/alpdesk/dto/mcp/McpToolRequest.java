package de.alpdesk.dto.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** A tool's switches; left out: unchanged. */
@Serdeable
public record McpToolRequest(@Nullable Boolean enabled, @Nullable Boolean changes) {
}
