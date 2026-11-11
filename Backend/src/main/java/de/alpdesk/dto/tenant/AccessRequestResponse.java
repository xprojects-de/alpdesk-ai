package de.alpdesk.dto.tenant;

import de.alpdesk.entities.tenant.AccessRequest;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/** Someone the WAM proxy let through who waits for the admin. */
@Serdeable
public record AccessRequestResponse(Long id, String username, String displayName, Instant requestedAt,
                                    @Nullable String language) {

    public static AccessRequestResponse from(AccessRequest request) {
        return new AccessRequestResponse(request.id(), request.username(), request.displayName(), request.requestedAt(),
                request.language());
    }
}
