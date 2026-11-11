package de.alpdesk.dto.tenant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * The tenant an access request goes to; active: approved at once (left out: the tenant admin approves), admin: a
 * tenant admin (left out: no), language: the user's frontend language (left out: English).
 */
@Serdeable
public record AccessAssignRequest(@Nullable Long tenantId, @Nullable Boolean active, @Nullable Boolean admin,
                                  @Nullable String language) {
}
