package de.alpdesk.dto.mcp;

import de.alpdesk.entities.mcp.McpTool;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record McpToolResponse(Long id, String name, String description, boolean changes, boolean enabled, boolean offered) {

    public static McpToolResponse from(McpTool tool) {
        return new McpToolResponse(tool.id(), tool.name(), tool.description(), tool.changes(), tool.enabled(),
                tool.offered());
    }
}
