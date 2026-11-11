package de.alpdesk.dto.task;

import io.micronaut.serde.annotation.Serdeable;

/** A run that was started - it runs in the background. */
@Serdeable
public record TaskRunStarted(Long runId) {
}
