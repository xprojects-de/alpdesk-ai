package de.alpdesk.entities.mcp;

import io.micronaut.core.annotation.Nullable;
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
        boolean offered,

        /**
         * Its whole entry as the server listed it (JSON: title, annotations, _meta, output schema) - what tools/list
         * passes on to MCP clients. Null until the tools are read again after V19, and for an A2A agent's skill.
         */
        @Nullable
        String definition
) {
}
