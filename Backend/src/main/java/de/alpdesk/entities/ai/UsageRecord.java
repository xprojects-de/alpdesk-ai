package de.alpdesk.entities.ai;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.math.BigDecimal;
import java.time.Instant;

@MappedEntity("usage_record")
public record UsageRecord(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        /** The tenant user who caused the call; null for background work (embedding documents). */
        @Nullable
        Long userId,

        AiProvider provider,

        String modelName,

        ModelKind kind,

        KeySource keySource,

        int inputTokens,

        int outputTokens,

        /** Tokens read from the provider's cache - not in inputTokens. */
        int cacheReadTokens,

        /** Tokens written to the provider's cache - not in inputTokens. */
        int cacheWriteTokens,

        /** The provider reported no token count: estimated from the text length. */
        boolean estimated,

        /** What the call costs at the model's price of that moment. */
        BigDecimal amount,

        Instant createdAt
) {
}
