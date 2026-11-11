package de.alpdesk.dto.login;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * The tenant the person chose at the sign-in through the WAM proxy (left out: none chosen yet) and the browser's
 * language - a user the sign-in creates speaks it, unless the person chose another in a tenant of its own.
 */
@Serdeable
public record WamSignInRequest(@Nullable Long tenantId, @Nullable String language) {
}
