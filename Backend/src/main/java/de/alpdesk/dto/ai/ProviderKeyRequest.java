package de.alpdesk.dto.ai;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ProviderKeyRequest(String apiKey) {
}
