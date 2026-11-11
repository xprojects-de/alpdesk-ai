package de.alpdesk.entities.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** A prompt a connected software offers, as read from its MCP server - off until a tenant admin turns it on. */
@MappedEntity("mcp_prompt")
public record McpPrompt(

        @Id
        @GeneratedValue
        Long id,

        Long connectionId,

        /** The prompt's name at the server. */
        String name,

        @Nullable
        String title,

        String description,

        /** Its arguments as the server gave them: a JSON list of {name, description, required}. */
        String arguments,

        boolean enabled,

        /** The server still lists it. */
        boolean offered
) {
}
