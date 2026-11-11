package de.alpdesk.dto.mcp;

import io.micronaut.serde.annotation.Serdeable;

/** A new MCP key - the only time the plain key is shown. */
@Serdeable
public record McpKeyCreatedResponse(McpKeyResponse key, String plainKey) {
}
