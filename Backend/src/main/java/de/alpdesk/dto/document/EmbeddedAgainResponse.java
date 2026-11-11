package de.alpdesk.dto.document;

import io.micronaut.serde.annotation.Serdeable;

/** How many documents are embedded again in the background. */
@Serdeable
public record EmbeddedAgainResponse(int documents) {
}
