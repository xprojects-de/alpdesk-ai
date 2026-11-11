package de.alpdesk.services.ai;

import de.alpdesk.entities.ai.KeySource;
import io.micronaut.core.annotation.Nullable;

/**
 * The API key a call uses (decrypted; null for a model on an own server that asks for none) and whose it is - which
 * decides whether the call is billed.
 */
public record ResolvedKey(@Nullable String apiKey, KeySource source) {
}
