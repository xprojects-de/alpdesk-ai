package de.alpdesk.dto.tenant;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** The ids of the models assigned to a tenant, active or not. */
@Serdeable
public record TenantModelsResponse(List<Long> modelIds) {
}
