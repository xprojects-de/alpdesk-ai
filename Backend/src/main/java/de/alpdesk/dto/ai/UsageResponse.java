package de.alpdesk.dto.ai;

import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.entities.ai.KeySource;
import de.alpdesk.entities.ai.ModelKind;
import de.alpdesk.entities.ai.UsageSummary;
import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;

/** The usage of one tenant with one model and key source in a month. */
@Serdeable
public record UsageResponse(
        Long tenantId,
        String tenantName,
        AiProvider provider,
        String modelName,
        ModelKind kind,
        KeySource keySource,
        long calls,
        long inputTokens,
        long outputTokens,
        /** Read from and written to the provider's cache - apart from inputTokens, each at its own price. */
        long cacheReadTokens,
        long cacheWriteTokens,
        /** Some token counts were estimated (the provider reported none). */
        boolean estimated,
        BigDecimal amount
) {
    public static UsageResponse from(UsageSummary summary) {
        return new UsageResponse(summary.tenantId(), summary.tenantName(), summary.provider(), summary.modelName(),
                summary.kind(), summary.keySource(), summary.calls(), summary.inputTokens(), summary.outputTokens(),
                summary.cacheReadTokens(), summary.cacheWriteTokens(),
                summary.estimated(), summary.amount());
    }
}
