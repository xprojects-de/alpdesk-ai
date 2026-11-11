package de.alpdesk.entities.ai;

import io.micronaut.core.annotation.Introspected;

import java.math.BigDecimal;

/** The usage of one tenant, model and key source summed up over a period - a query result, no table. */
@Introspected
public record UsageSummary(
        Long tenantId,
        String tenantName,
        AiProvider provider,
        String modelName,
        ModelKind kind,
        KeySource keySource,
        long calls,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        boolean estimated,
        BigDecimal amount
) {
}
