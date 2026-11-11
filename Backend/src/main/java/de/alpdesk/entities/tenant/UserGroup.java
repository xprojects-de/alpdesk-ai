package de.alpdesk.entities.tenant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/**
 * A group of a tenant's users ("Vertrieb", "Werkstatt"): its members get its level for each connected software and
 * find its knowledge areas. With a WAM proxy that sends the person's IDM groups, a group with a wamGroup counts for a
 * member only while the proxy lists that IDM group.
 */
@MappedEntity("user_group")
public record UserGroup(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        String name,

        @Nullable
        String wamGroup,

        /** Whether its members may search the web in the chat (docs/architecture/ai-features.md "Web search"). */
        boolean webSearch,

        Instant createdAt
) {
}
