package de.alpdesk.dto.document;

import io.micronaut.serde.annotation.Serdeable;

/** How many documents were deleted. */
@Serdeable
public record DeletedDocumentsResponse(int deleted) {
}
