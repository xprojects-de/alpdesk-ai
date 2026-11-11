package de.alpdesk.dto.filestore;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Map;

/**
 * What checking a store found. status: OK (logged in, the folder read - entries are its first names), UNCONFIRMED (the
 * server's fingerprint is shown to be confirmed - nothing was logged in to), CHANGED (another key or certificate than
 * the one confirmed - nothing was logged in to), FAILED (errorCode and errorParams say why). store: the store with the
 * check's outcome.
 */
@Serdeable
public record FileStoreCheckResponse(
        String status,
        String fingerprint,
        List<String> entries,
        String errorCode,
        Map<String, String> errorParams,
        FileStoreResponse store
) {
}
