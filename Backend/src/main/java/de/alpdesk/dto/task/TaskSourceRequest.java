package de.alpdesk.dto.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** A task's text to check without saving it. */
@Serdeable
public record TaskSourceRequest(@Nullable String source) {
}
