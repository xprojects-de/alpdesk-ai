package de.alpdesk.dto.account;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * The tenant to switch to; password: the target user's - required without the WAM proxy (a username proves no
 * person there), ignored with it.
 */
@Serdeable
public record SwitchTenantRequest(@Nullable Long tenantId, @Nullable String password) {
}
