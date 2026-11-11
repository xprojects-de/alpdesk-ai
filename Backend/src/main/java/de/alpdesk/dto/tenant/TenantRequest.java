package de.alpdesk.dto.tenant;

import de.alpdesk.entities.tenant.SearchLanguage;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

@Serdeable
@Schema(description = "Request object for creating or updating a tenant")
public record TenantRequest(
        @Schema(description = "Display name, unique (case-insensitive)", example = "Musterfirma GmbH", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Nullable
        @Schema(description = "Whether the tenant may use the API - default true on create; left out on update = unchanged",
                example = "true")
        Boolean active,

        @Nullable
        @Schema(description = "Whether the tenant may use the admin's API keys where it has none of its own (billed) - "
                + "default false on create; left out on update = unchanged")
        Boolean globalKeysAllowed,

        @Nullable
        @Schema(description = "Whether the tenant's admins may keep scheduled tasks - default false on create; left out "
                + "on update = unchanged")
        Boolean tasksAllowed,
        @Nullable
        @Schema(description = "Whether the tenant may connect A2A agents - default false on create; left out on update = "
                + "unchanged")
        Boolean a2aAllowed,

        @Nullable
        @Schema(description = "The tenant's own sandbox instances for Python and shell in the chat, comma separated "
                + "(http or https) - left out on update = unchanged, empty = no code execution (removes the token too)",
                example = "http://alpdesk-ai-sandbox-musterfirma:8080")
        String sandboxUrls,

        @Nullable
        @Schema(description = "The token the tenant's sandbox instances were started with, at least 32 characters - "
                + "write only; left out or empty on update = unchanged")
        String sandboxToken,

        @Nullable
        @Schema(description = "The language the full-text search reads the tenant's documents in - on create only, "
                + "default SIMPLE (no language); changed through .../search-language, since it builds the search again")
        SearchLanguage searchLanguage,

        @Nullable
        @Schema(description = "Contact e-mail address", example = "info@musterfirma.example")
        String contactEmail,

        @Nullable
        @Schema(description = "Free-text notes for the admin")
        String notes
) {
}
