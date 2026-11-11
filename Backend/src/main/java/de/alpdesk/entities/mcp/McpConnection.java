package de.alpdesk.entities.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** The tenant's own software connected through its MCP server (docs/architecture/mcp.md "MCP clients"). */
@MappedEntity("mcp_connection")
public record McpConnection(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        String name,

        /** The software's MCP endpoint, e.g. "https://software.example.com/mcp". */
        String baseUrl,

        /** Its key, encrypted (security.SecretCipher) - never sent out; null when it asks for none. */
        @Nullable
        String apiKey,

        /** The key's last characters, to recognise it. */
        @Nullable
        String keyHint,

        /** When its tools were read last; null: never. */
        @Nullable
        Instant toolsLoadedAt,

        /**
         * MCP keys with it get its reading tools, prompts and instructions directly ("Direkt an MCP-Clients") - off: they
         * use it through ask_assistant only. Which keys may use it at all is the keys' choice (tenant_mcp_key_connection).
         */
        boolean passesThrough,

        /** The instructions its server gives in initialize, cut to McpConnectionService.MAX_INSTRUCTIONS; null: none. */
        @Nullable
        String instructions,

        /** MCP or A2A - fixed when the connection is made. An A2A agent's skills are kept as its tools. */
        McpConnectionProtocol protocol,

        /** An A2A agent that answers every question of the tenant's chat - at most one per tenant. */
        boolean answersChat,

        Instant createdAt,

        Instant updatedAt
) {
}
