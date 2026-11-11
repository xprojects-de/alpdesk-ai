package de.alpdesk.dto.oauth;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Completing a connection's sign-in on the page the browser came back to: the server's answer as our callback passed it
 * on - its query (state, code, iss, or error).
 */
@Serdeable
public record SignInCompleteRequest(@Nullable String callback) {
}
