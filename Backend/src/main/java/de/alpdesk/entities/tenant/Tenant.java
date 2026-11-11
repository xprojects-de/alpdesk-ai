package de.alpdesk.entities.tenant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("tenant")
public record Tenant(

        @Id
        @GeneratedValue
        Long id,

        String name,

        boolean active,

        @Nullable
        String contactEmail,

        @Nullable
        String notes,

        /** The tenant's own database on the same PostgreSQL server - see services.tenant.TenantDatabases. */
        String databaseName,

        /** Whether it may use the admin's API keys where it has none of its own (the usage is then billed). */
        boolean globalKeysAllowed,

        /** Whether its tenant admins may keep scheduled tasks (docs/architecture/tasks.md) - the admin's decision. */
        boolean tasksAllowed,

        /** Whether its connected software may include A2A agents (docs/architecture/a2a.md) - the admin's decision. */
        boolean a2aAllowed,

        /**
         * Its own sandbox instances for the chat's Python and shell, comma separated (docs/architecture/code-execution.md);
         * null: no code execution. Never shared with another tenant.
         */
        @Nullable
        String sandboxUrls,

        /** The token its instances were started with, encrypted (security.SecretCipher) - never sent out. */
        @Nullable
        String sandboxToken,

        /** The token's last characters, to recognise it. */
        @Nullable
        String sandboxTokenHint,

        /** The one model its documents are embedded with; null until a tenant user chooses one. */
        @Nullable
        Long embeddingModelId,

        /** The reranker its knowledge search reorders the candidates with (docs/architecture/ai-features.md "Reranking"); null: off. */
        @Nullable
        Long rerankingModelId,

        /** The language the full-text search reads its documents in; changing it builds that search's index again. */
        SearchLanguage searchLanguage,

        /** Random, in the address of the tenant's own login page /login/<key> (docs/architecture/branding.md). */
        String loginKey,

        Instant createdAt,

        Instant updatedAt
) {
}
