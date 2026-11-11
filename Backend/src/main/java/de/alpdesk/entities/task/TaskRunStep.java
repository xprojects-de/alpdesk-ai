package de.alpdesk.entities.task;

import io.micronaut.core.annotation.Nullable;

import java.math.BigDecimal;
import java.time.Instant;

/** What one step did in a run, per round (an edge back runs it again) - tenant database, plain record. */
public record TaskRunStep(
        String stepId,
        int round,
        StepState state,
        /** The structured result as JSON (services.task.TaskResults); null for a skipped step; a failed one's errorParams. */
        @Nullable String result,
        @Nullable String errorCode,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        /** What its calls cost, as the usage recorded them; null for a step from before costs were kept. */
        @Nullable BigDecimal cost,
        Instant startedAt,
        Instant endedAt
) {
}
