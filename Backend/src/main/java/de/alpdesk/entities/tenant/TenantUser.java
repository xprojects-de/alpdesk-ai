package de.alpdesk.entities.tenant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("tenant_user")
public record TenantUser(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        /**
         * Unique per tenant (case-insensitive): one person may have a user in several tenants, with the same username
         * (docs/architecture/tenancy.md "Who does what").
         */
        String username,

        String displayName,

        /** See security.PasswordHasher - never the password itself; null: signs in through the WAM proxy only. */
        @Nullable
        String passwordHash,

        boolean active,

        /** Keeps the tenant's settings (users, API keys, embedding model, company knowledge, usage) and chats too. */
        boolean admin,

        Instant createdAt,

        Instant updatedAt,

        @Nullable
        Instant lastLoginAt,

        /** Tokens issued before it are refused - set when an admin sets a new password; null: all are valid. */
        @Nullable
        Instant sessionsValidFrom,

        /** The frontend's language (docs/architecture/i18n.md); null: from before the column - English. */
        @Nullable
        String language
) {
}
