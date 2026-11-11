package de.alpdesk.entities.tenant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** Someone the WAM proxy let through who is no tenant user yet - waiting for the admin (security.WamProxy). */
@MappedEntity("access_request")
public record AccessRequest(
        @Id
        @GeneratedValue
        Long id,
        /** The WAM proxy's user name - becomes the tenant user's username. */
        String username,
        String displayName,
        Instant requestedAt,
        /** The language of the browser it signed in with - null when it sent none. */
        @Nullable String language
) {
}
