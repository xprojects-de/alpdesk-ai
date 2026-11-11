package de.alpdesk.dto.mcp;

import de.alpdesk.entities.mcp.McpAccessLevel;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A group of the tenant's users and what its members may do with the connected software; level null: nothing.
 * wamGroup: the IDM group the WAM proxy must list for a member, if the group names one.
 */
@Serdeable
public record McpAccessResponse(Long groupId, String name, @Nullable String wamGroup, McpAccessLevel level) {
}
