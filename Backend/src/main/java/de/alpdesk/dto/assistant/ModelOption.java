package de.alpdesk.dto.assistant;

import io.micronaut.serde.annotation.Serdeable;

/** A chat model the tenant may use - to choose as its default model. */
@Serdeable
public record ModelOption(Long id, String displayName) {
}
