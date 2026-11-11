package de.alpdesk.dto.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A run in a list.
 *
 * @param state     QUEUED, RUNNING, DONE, UNCLEAR, FAILED or CANCELLED
 * @param startedBy ADMIN, TENANT_ADMIN, SCHEDULE, API or MCP
 * @param startedByKey the API key it was started with (API)
 * @param cacheReadTokens  read from the provider's cache - not in inputTokens
 * @param cost      what its steps cost: as the usage recorded it; a run from before costs were kept computed at the
 *                  catalog's current price (RunCosts) - null for one of those in the task list
 * @param costPartial computed, and a step used tokens whose model is no longer in the catalog - at least that much
 */
@Serdeable
public record TaskRunSummary(Long id, int taskVersion, String state, String startedBy, @Nullable Long startedByUser,
                             @Nullable Long startedByKey, boolean cancelRequested,
                             @Nullable String errorCode, @Nullable String errorMessage, boolean mailFailed,
                             long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                             Instant createdAt, @Nullable Instant startedAt,
                             @Nullable Instant endedAt, @Nullable BigDecimal cost, boolean costPartial) {
}
