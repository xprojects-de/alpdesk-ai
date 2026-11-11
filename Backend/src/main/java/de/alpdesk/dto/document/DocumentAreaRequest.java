package de.alpdesk.dto.document;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** The area a document moves to; null: the company knowledge. */
@Serdeable
public record DocumentAreaRequest(@Nullable Long areaId) {
}
