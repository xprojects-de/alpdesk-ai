package de.alpdesk.dto.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * The check of an A2A agent (docs/architecture/a2a.md "Operations"): whether its card could be read, the name and
 * number of skills it gives, how long it took - and why not.
 */
@Serdeable
public record McpConnectionCheckResponse(boolean ok, @Nullable String name, int skills, long millis,
                                         @Nullable String error) {
}
