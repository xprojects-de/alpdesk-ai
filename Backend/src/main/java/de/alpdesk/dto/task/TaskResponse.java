package de.alpdesk.dto.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;

/** A task with its text as written and its steps as compiled; keys: the API keys and MCP keys that may start it. */
@Serdeable
public record TaskResponse(Long id, String name, boolean active, List<TaskKeyResponse> keys, int version, String source,
                           List<String> triggers, @Nullable Instant nextRunAt, List<TaskStepView> steps,
                           @Nullable TaskRunSummary lastRun, Instant createdAt, Instant updatedAt) {
}
