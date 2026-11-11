package de.alpdesk.dto.document;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** A knowledge area to create or rename. */
@Serdeable
public record KnowledgeAreaRequest(@Nullable String name) {
}
