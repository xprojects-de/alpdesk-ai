package de.alpdesk.dto.ai;

import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.entities.ai.ProviderKey;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/** A stored API key without the key itself - only its last characters. */
@Serdeable
public record ProviderKeyResponse(
        AiProvider provider,
        String keyHint,
        Instant updatedAt
) {
    public static ProviderKeyResponse from(ProviderKey key) {
        return new ProviderKeyResponse(key.provider(), key.keyHint(), key.updatedAt());
    }
}
