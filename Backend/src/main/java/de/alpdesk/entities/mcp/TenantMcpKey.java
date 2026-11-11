package de.alpdesk.entities.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/**
 * One of the tenant's keys for its MCP server - only its hash is stored. Its knowledge areas are kept in
 * tenant_mcp_key_area.
 */
@MappedEntity("tenant_mcp_key")
public record TenantMcpKey(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        /** Tells the keys apart ("Entwicklung", "Geschäftsleitung"). */
        String name,

        /**
         * What the key releases, in the tenant admin's words - sent to every client of the key (initialize, the
         * descriptions of search_knowledge and ask_assistant); null: none.
         */
        @Nullable
        String description,

        /** The first characters of the key, shown so it can be recognised. */
        String keyPrefix,

        /** Hex SHA-256 of the full key. */
        String keyHash,

        /** Whether the questions asked with it may search the web. */
        boolean webSearch,

        /** It opens /mcp (Claude Code, Copilot, another chat's connection). */
        boolean mcp,

        /** It opens /a2a: the tenant's assistant as an A2A agent (docs/architecture/a2a.md). */
        boolean a2a,

        Instant createdAt,

        @Nullable
        Instant lastUsedAt
) {
}
