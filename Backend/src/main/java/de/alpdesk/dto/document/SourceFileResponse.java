package de.alpdesk.dto.document;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/**
 * A file of a knowledge source without a document: reason the refusal's code (document.*, source.*) or the store's
 * (fileStore.*: it could not be read), or null - its document was deleted by hand.
 */
@Serdeable
public record SourceFileResponse(String path, long size, Instant modified, String reason) {
}
