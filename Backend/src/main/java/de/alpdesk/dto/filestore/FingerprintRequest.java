package de.alpdesk.dto.filestore;

import io.micronaut.serde.annotation.Serdeable;

/** The server's fingerprint as a check showed it ("SHA256:..."), confirmed by a tenant admin. */
@Serdeable
public record FingerprintRequest(String fingerprint) {
}
