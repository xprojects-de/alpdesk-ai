package de.alpdesk.dto.tenant;

import de.alpdesk.entities.mcp.McpAccessLevel;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * What a group's members may use of one connected software: READ its reading tools, WRITE all its tools (the chat
 * asking before every change). A connection without an entry: no access.
 */
@Serdeable
public record UserGroupLevel(Long connectionId, @Nullable McpAccessLevel level) {
}
