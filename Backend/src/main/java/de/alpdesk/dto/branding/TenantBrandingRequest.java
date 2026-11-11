package de.alpdesk.dto.branding;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** A tenant's name and primary colour ("#rrggbb"); null or empty = the installation's default. */
@Serdeable
public record TenantBrandingRequest(@Nullable String name, @Nullable String primaryColor) {
}
