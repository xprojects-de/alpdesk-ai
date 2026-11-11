package de.alpdesk.entities.mcp;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** A tool a connected software offers, as read from its MCP server - off until a tenant admin turns it on. */
@MappedEntity("mcp_tool")
public record McpTool(

        @Id
        @GeneratedValue
        Long id,

        Long connectionId,

        /** The tool's name at the server. */
        String name,

        String description,

        /** Its input JSON schema, as the server gave it. */
        String inputSchema,

        /** It changes something there: asked before every call, only for users who may change. */
        boolean changes,

        /** The server's readOnlyHint when it was last read - a change of it overrides the admin's correction. */
        boolean readOnlyHint,

        boolean enabled,

        /** The server still lists it. */
        boolean offered
) {
}
