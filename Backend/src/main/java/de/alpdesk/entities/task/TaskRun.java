package de.alpdesk.entities.task;

import io.micronaut.core.annotation.Nullable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One run of a task (tenant database, plain record): with the definition it ran with, so a later save does not change
 * what it shows.
 */
public record TaskRun(
        Long id,
        Long taskId,
        int taskVersion,
        String definition,
        RunState state,
        StartedBy startedBy,
        /** The tenant user who started it; null for the admin, the schedule, the client software and MCP. */
        @Nullable Long startedByUser,
        /** The API key it was started with (StartedBy.API). */
        @Nullable Long startedByKey,
        /** A cancel was asked for - the run ends before its next step or round. */
        boolean cancelRequested,
        @Nullable String errorCode,
        @Nullable String errorMessage,
        /** A mail of the run could not be sent - the run itself is not failed by it. */
        boolean mailFailed,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        /** What its steps cost, as the usage recorded them; null for a run from before costs were kept. */
        @Nullable BigDecimal cost,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant endedAt
) {
}
