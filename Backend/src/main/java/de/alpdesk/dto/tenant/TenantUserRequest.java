package de.alpdesk.dto.tenant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Serdeable
@Schema(description = "Request object for creating or updating a tenant user")
public record TenantUserRequest(
        @Schema(description = "Login name, unique across all tenants (case-insensitive): letters, digits, . _ - @",
                example = "maria.huber", requiredMode = Schema.RequiredMode.REQUIRED)
        String username,

        @Schema(description = "Name shown in the frontend", example = "Maria Huber", requiredMode = Schema.RequiredMode.REQUIRED)
        String displayName,

        @Nullable
        @Schema(description = "At least 12 characters - required on create; left out on update = unchanged")
        String password,

        @Nullable
        @Schema(description = "Whether the user may log in - default true on create; left out on update = unchanged")
        Boolean active,

        @Nullable
        @Schema(description = "Whether the user is a tenant admin - default false on create; left out on update = unchanged")
        Boolean admin,

        @Nullable
        @Schema(description = "The user's groups - its levels for the connected software and its knowledge areas come from them, and without one a user who is no tenant admin does not get in; left out on create = the tenant's group \"Standard\", [] = none; left out on update = unchanged")
        List<Long> groupIds,

        @Nullable
        @Schema(description = "The language of the user's frontend, a language tag such as \"en\" or \"de\" - default \"en\" on create; left out on update = unchanged", example = "en")
        String language
) {
}
