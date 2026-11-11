package de.alpdesk.dto.mcp;

import de.alpdesk.entities.mcp.TenantMcpKey;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;

/**
 * One of the tenant's MCP keys as shown - its prefix, never the key; description: what it releases, in the tenant
 * admin's words; areaIds: what it finds besides the company knowledge;
 * connectionIds: the connected software it may use; taskIds: the scheduled tasks it may list and start.
 */
@Serdeable
public record McpKeyResponse(Long id, String name, @Nullable String description, String keyPrefix, boolean webSearch, List<Long> areaIds,
                             List<Long> connectionIds, List<Long> taskIds, Instant createdAt,
                             @Nullable Instant lastUsedAt,
                             /** The doors it opens: /mcp and /a2a. */
                             boolean mcp, boolean a2a) {

    public static McpKeyResponse from(TenantMcpKey key, List<Long> areaIds, List<Long> connectionIds,
                                      List<Long> taskIds) {
        return new McpKeyResponse(key.id(), key.name(), key.description(), key.keyPrefix(), key.webSearch(), List.copyOf(areaIds),
                List.copyOf(connectionIds), List.copyOf(taskIds), key.createdAt(), key.lastUsedAt(), key.mcp(), key.a2a());
    }
}
