package de.alpdesk.entities.branding;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** A tenant's own look; a null value means the installation's default (docs/architecture/branding.md). */
@MappedEntity("tenant_branding")
public record TenantBranding(

        @Id
        Long tenantId,

        @Nullable
        String name,

        /** "#rrggbb", lower case. */
        @Nullable
        String primaryColor,

        /** The logo's media type; null without a logo (the file is in the file storage). */
        @Nullable
        String logoType,

        Instant updatedAt
) {
}
