package de.alpdesk.dto.oauth;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;

/**
 * A connection's sign-in as the tenant admin sees it - never a token, a secret or a verifier. status: AVAILABLE,
 * PENDING, SIGNED_IN or SIGN_IN_NEEDED; issuer: the sign-in server's host, to recognise it; clientIdSource:
 * METADATA_DOCUMENT, REGISTERED, ENTERED or null; redirectUri: our callback address, to register by hand at the
 * server's portal; clientIdNeeded: the server takes no registration - its client id must be entered.
 */
@Serdeable
public record GrantView(String status, List<String> scopesGranted, List<String> scopesOffered,
                        @Nullable Instant expiresAt, @Nullable String lastError, String issuer,
                        @Nullable String clientIdSource, @Nullable String redirectUri, boolean clientIdNeeded) {
}
