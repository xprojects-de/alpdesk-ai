package de.alpdesk.dto.tenant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * A group of the tenant's users. wamGroup: the IDM group the WAM proxy must list for a member - empty: none; left out:
 * none on create, unchanged on update. userIds and areaIds: none on create; left out on update = unchanged. webSearch:
 * whether its members may search the web - left out: off on create, unchanged on update. levels: what its members may
 * use of the connected software, one entry per connection - none on create; left out on update = unchanged; given, they
 * replace the group's levels (a connection left out: no access).
 */
@Serdeable
public record UserGroupRequest(String name, @Nullable String wamGroup, @Nullable List<Long> userIds,
                               @Nullable List<Long> areaIds, @Nullable Boolean webSearch,
                               @Nullable List<UserGroupLevel> levels) {
}
