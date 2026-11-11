package de.alpdesk.dto.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * Creating or changing one of the tenant's MCP keys: name required on create, on a change left out = unchanged;
 * description (what the key releases, sent to its clients, at most 500 characters) left out on create = none, on a
 * change = unchanged, empty = removed;
 * webSearch left out on create = off; areaIds (besides the company knowledge), connectionIds (the connected software
 * it may use) and taskIds (the scheduled tasks it may list and start) left out on create = none;
 * changingConnectionIds: of its software, those whose changing tools go to its MCP client too (roadmap point 68) - left
 * out on create = none, on a change = the ones it had that it still may use.
 */
@Serdeable
public record McpKeyRequest(@Nullable String name, @Nullable String description, @Nullable Boolean webSearch, @Nullable List<Long> areaIds,
                            @Nullable List<Long> connectionIds, @Nullable List<Long> taskIds,
                            /** Opens /mcp - left out: on for a new key, unchanged on a change. */
                            @Nullable Boolean mcp,
                            /** Opens /a2a - left out: off for a new key, unchanged on a change. */
                            @Nullable Boolean a2a,
                            @Nullable List<Long> changingConnectionIds) {
}
