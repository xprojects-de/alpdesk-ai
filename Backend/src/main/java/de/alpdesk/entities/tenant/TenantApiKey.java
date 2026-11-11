package de.alpdesk.entities.tenant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("tenant_api_key")
public record TenantApiKey(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        String name,

        /** The first characters of the key, shown in the UI so a key can be recognised. */
        String keyPrefix,

        /** Hex SHA-256 of the full key - the key itself is never stored. */
        String keyHash,

        /** False: its chat questions are answered and forgotten - nothing but the usage is stored. */
        boolean keepsHistory,

        /** Whether its chat questions may search the web (docs/architecture/ai-features.md "Web search"). */
        boolean webSearch,

        Instant createdAt,

        @Nullable
        Instant lastUsedAt
) {
}
