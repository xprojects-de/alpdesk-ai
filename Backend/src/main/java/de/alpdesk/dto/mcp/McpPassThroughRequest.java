package de.alpdesk.dto.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** Whether MCP keys with the connection get it directly ("Direkt an MCP-Clients") - required. */
@Serdeable
public record McpPassThroughRequest(@Nullable Boolean passesThrough) {
}
