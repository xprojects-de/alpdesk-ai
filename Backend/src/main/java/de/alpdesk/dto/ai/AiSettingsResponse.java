package de.alpdesk.dto.ai;

import de.alpdesk.entities.tenant.SearchLanguage;
import de.alpdesk.entities.tenant.Tenant;
import io.micronaut.serde.annotation.Serdeable;

/** The tenant's AI settings as its users see them. */
@Serdeable
public record AiSettingsResponse(
        /** The model the tenant's documents are embedded with; null until one is chosen. */
        Long embeddingModelId,
        /** The reranker the knowledge search reorders its candidates with; null: off. */
        Long rerankingModelId,
        /** The language the full-text search reads the tenant's documents in. */
        SearchLanguage searchLanguage,
        /** Whether the admin's keys may be used where the tenant has none (billed). */
        boolean globalKeysAllowed
) {
    public static AiSettingsResponse from(Tenant tenant) {
        return new AiSettingsResponse(tenant.embeddingModelId(), tenant.rerankingModelId(), tenant.searchLanguage(),
                tenant.globalKeysAllowed());
    }
}
