package de.alpdesk.dto.tenant;

import de.alpdesk.entities.tenant.TenantUser;
import de.alpdesk.services.tenant.TenantUserService;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Serdeable
@Schema(description = "A tenant user, without the password")
public record TenantUserResponse(
        Long id,
        String username,
        String displayName,
        boolean active,
        boolean admin,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        @Schema(description = "The user's groups")
        List<Long> groupIds,
        @Schema(description = "The IDM groups the WAM proxy sent on the user's last request, lower case - empty unless they are checked")
        List<String> wamGroups,
        @Schema(description = "The language of the user's frontend, chosen by an admin or by the user")
        String language
) {
    public static TenantUserResponse from(TenantUser user, List<Long> groupIds, List<String> wamGroups) {
        return new TenantUserResponse(user.id(), user.username(), user.displayName(), user.active(), user.admin(),
                user.createdAt(), user.updatedAt(), user.lastLoginAt(), List.copyOf(groupIds), List.copyOf(wamGroups), languageOf(user));
    }

    /** A user from before the column has none - English, like the frontend's default. */
    public static String languageOf(TenantUser user) {
        return user.language() == null ? TenantUserService.DEFAULT_LANGUAGE : user.language();
    }
}
