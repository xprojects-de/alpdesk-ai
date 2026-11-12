package de.alpdesk.dto.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** Whether MCP keys that pass the connection through read its resources ("Ressourcen an MCP-Clients") - required. */
@Serdeable
public record McpPassResourcesRequest(@Nullable Boolean passesResources) {
}
