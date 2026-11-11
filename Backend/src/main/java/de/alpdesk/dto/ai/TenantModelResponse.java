package de.alpdesk.dto.ai;

import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.entities.ai.KeySource;
import de.alpdesk.entities.ai.ModelKind;
import io.micronaut.serde.annotation.Serdeable;

/** An active model as a tenant user sees it: without prices, with whether the tenant has a key for it. */
@Serdeable
public record TenantModelResponse(
        Long id,
        AiProvider provider,
        ModelKind kind,
        String displayName,
        Integer dimension,
        /** Whose key a call would use - null when the tenant has no key for the provider. */
        KeySource keySource
) {
}
