package de.alpdesk.dto.tenant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A user of another tenant the admin adds to this one (the same person): active (left out: yes) or waiting for the
 * tenant admin, a tenant admin (left out: no).
 */
@Serdeable
public record UserFromTenantRequest(@Nullable Long userId, @Nullable Boolean active, @Nullable Boolean admin) {
}
