package de.alpdesk.dto.tenant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** The models a tenant may use - replaces the previous assignment. */
@Serdeable
public record TenantModelsRequest(@Nullable List<Long> modelIds) {
}
