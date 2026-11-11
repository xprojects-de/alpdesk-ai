package de.alpdesk.dto.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;

/**
 * A task in the list - with its last run, null when it never ran.
 *
 * @param triggers  its schedules as written ("every 5h", "cron 0 30 6 * * MON-FRI")
 * @param nextRunAt when its schedule is due next; null: none
 */
@Serdeable
public record TaskSummaryResponse(Long id, String name, boolean active, int version,
                                  List<String> triggers, @Nullable Instant nextRunAt, @Nullable TaskRunSummary lastRun,
                                  Instant createdAt, Instant updatedAt) {
}
