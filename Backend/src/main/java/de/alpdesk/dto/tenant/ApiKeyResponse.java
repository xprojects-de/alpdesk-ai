package de.alpdesk.dto.tenant;

import de.alpdesk.entities.tenant.TenantApiKey;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Serdeable
@Schema(description = "An API key without its secret part")
public record ApiKeyResponse(
        Long id,
        String name,
        @Schema(description = "First characters of the key, for recognising it", example = "ai_Ab3dE9xY")
        String keyPrefix,
        @Schema(description = "False: its chat questions are answered and forgotten - nothing but the usage is stored")
        boolean keepsHistory,
        @Schema(description = "Whether its chat questions may search the web")
        boolean webSearch,
        Instant createdAt,
        Instant lastUsedAt,
        @Schema(description = "The knowledge areas the key finds and pushes into besides the company knowledge")
        List<Long> areaIds,
        @Schema(description = "The scheduled tasks the key may start")
        List<Long> taskIds,
        @Schema(description = "The connected software its chat questions may use")
        List<Long> connectionIds
) {
    public static ApiKeyResponse from(TenantApiKey key, List<Long> areaIds, List<Long> taskIds,
                                      List<Long> connectionIds) {
        return new ApiKeyResponse(key.id(), key.name(), key.keyPrefix(), key.keepsHistory(), key.webSearch(),
                key.createdAt(), key.lastUsedAt(), List.copyOf(areaIds), List.copyOf(taskIds), List.copyOf(connectionIds));
    }
}
