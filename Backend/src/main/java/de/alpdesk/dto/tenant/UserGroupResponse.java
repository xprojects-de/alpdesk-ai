package de.alpdesk.dto.tenant;

import de.alpdesk.entities.tenant.UserGroup;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;

/** A group with its members, the knowledge areas they find and their levels per connected software. */
@Serdeable
public record UserGroupResponse(Long id, String name, @Nullable String wamGroup, List<Long> userIds,
                                List<Long> areaIds, boolean webSearch, List<UserGroupLevel> levels,
                                Instant createdAt) {

    public static UserGroupResponse from(UserGroup group, List<Long> userIds, List<Long> areaIds,
                                         List<UserGroupLevel> levels) {
        return new UserGroupResponse(group.id(), group.name(), group.wamGroup(), List.copyOf(userIds),
                List.copyOf(areaIds), group.webSearch(), List.copyOf(levels), group.createdAt());
    }
}
