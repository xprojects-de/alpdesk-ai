package de.alpdesk.dto.task;

import io.micronaut.serde.annotation.Serdeable;

/** The task format's reference as Markdown. */
@Serdeable
public record TaskReferenceResponse(String markdown) {
}
