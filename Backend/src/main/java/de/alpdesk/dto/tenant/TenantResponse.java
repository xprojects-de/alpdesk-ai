package de.alpdesk.dto.tenant;

import de.alpdesk.entities.tenant.SearchLanguage;
import de.alpdesk.entities.tenant.Tenant;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Serdeable
@Schema(description = "A tenant")
public record TenantResponse(
        Long id,
        String name,
        boolean active,
        String contactEmail,
        String notes,
        @Schema(description = "The tenant's own database (for backups and support)")
        String databaseName,
        boolean globalKeysAllowed,
        @Schema(description = "Whether the tenant's admins may keep scheduled tasks")
        boolean tasksAllowed,
        @Schema(description = "Whether the tenant may connect A2A agents")
        boolean a2aAllowed,
        @Schema(description = "Whether the tenant's chat may run Python and shell: its sandbox instances and their token "
                + "are set")
        boolean codeExecution,
        @Schema(description = "The tenant's own sandbox instances, comma separated; null: none")
        String sandboxUrls,
        @Schema(description = "The last characters of the sandbox token - the token itself is never sent out")
        String sandboxTokenHint,
        Long embeddingModelId,
        @Schema(description = "The reranker of the knowledge search; null: off")
        Long rerankingModelId,
        @Schema(description = "The language the full-text search reads the tenant's documents in")
        SearchLanguage searchLanguage,
        Instant createdAt,
        Instant updatedAt
) {
    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(tenant.id(), tenant.name(), tenant.active(), tenant.contactEmail(),
                tenant.notes(), tenant.databaseName(), tenant.globalKeysAllowed(), tenant.tasksAllowed(), tenant.a2aAllowed(),
                tenant.sandboxUrls() != null && tenant.sandboxToken() != null, tenant.sandboxUrls(),
                tenant.sandboxTokenHint(), tenant.embeddingModelId(), tenant.rerankingModelId(), tenant.searchLanguage(), tenant.createdAt(), tenant.updatedAt());
    }
}
