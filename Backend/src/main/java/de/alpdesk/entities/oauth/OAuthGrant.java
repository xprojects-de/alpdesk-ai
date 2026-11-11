package de.alpdesk.entities.oauth;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/**
 * A connection's sign-in at the authorization server its software named (docs/architecture/mcp.md "Signing in to the
 * software's server"): the server's endpoints, our client id there, the pending sign-in and the tokens - one per
 * connection. Secrets, the verifier and the tokens are encrypted (security.SecretCipher). The with-methods are the
 * grant's transitions; services.oauth.OAuthClient decides when each happens.
 */
@MappedEntity("oauth_grant")
public record OAuthGrant(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        Long connectionId,

        GrantStatus status,

        /** The resource indicator (RFC 8707): the connection's address. */
        String resource,

        String issuer,

        String authorizationEndpoint,

        String tokenEndpoint,

        @Nullable
        String registrationEndpoint,

        /** The server reads a client id metadata document - false also once it could not read ours. */
        boolean metadataDocumentSupported,

        /** The server announced that it sends iss with the callback (RFC 9207) - then it must come. */
        boolean issuerParameterRequired,

        /** Space-separated, as the server lists them; null: none. */
        @Nullable
        String scopesOffered,

        @Nullable
        String clientId,

        @Nullable
        ClientIdSource clientIdSource,

        /** Encrypted; null for a public client. */
        @Nullable
        String clientSecret,

        /** none, client_secret_basic or client_secret_post - before registration the method we will ask for. */
        @Nullable
        String tokenEndpointAuthMethod,

        @Nullable
        String redirectUri,

        @Nullable
        String state,

        /** Encrypted. */
        @Nullable
        String codeVerifier,

        @Nullable
        String returnUrl,

        @Nullable
        Long startedByUserId,

        @Nullable
        Instant pendingUntil,

        /** Encrypted. */
        @Nullable
        String accessToken,

        /** Encrypted. */
        @Nullable
        String refreshToken,

        @Nullable
        Instant expiresAt,

        @Nullable
        String scopesGranted,

        @Nullable
        String lastError,

        Instant createdAt,

        Instant updatedAt
) {

    /** The server as found anew - the client id and the tokens stay, they belong to the same issuer. */
    public OAuthGrant withServer(String resource, String authorizationEndpoint, String tokenEndpoint,
                                 @Nullable String registrationEndpoint, boolean metadataDocumentSupported,
                                 boolean issuerParameterRequired, @Nullable String scopesOffered,
                                 @Nullable String tokenEndpointAuthMethod) {
        return new OAuthGrant(id, tenantId, connectionId, status, resource, issuer, authorizationEndpoint,
                tokenEndpoint, registrationEndpoint, metadataDocumentSupported, issuerParameterRequired, scopesOffered,
                clientId, clientIdSource, clientSecret,
                clientId == null ? tokenEndpointAuthMethod : this.tokenEndpointAuthMethod, redirectUri, state,
                codeVerifier, returnUrl, startedByUserId, pendingUntil, accessToken, refreshToken, expiresAt,
                scopesGranted, lastError, createdAt, Instant.now());
    }

    public OAuthGrant withClient(@Nullable String clientId, @Nullable ClientIdSource source,
                                 @Nullable String clientSecret, @Nullable String tokenEndpointAuthMethod,
                                 @Nullable String redirectUri) {
        return new OAuthGrant(id, tenantId, connectionId, status, resource, issuer, authorizationEndpoint,
                tokenEndpoint, registrationEndpoint, metadataDocumentSupported, issuerParameterRequired, scopesOffered,
                clientId, source, clientSecret, tokenEndpointAuthMethod, redirectUri, state, codeVerifier, returnUrl,
                startedByUserId, pendingUntil, accessToken, refreshToken, expiresAt, scopesGranted, lastError,
                createdAt, Instant.now());
    }

    /** The server could not read our client document: the next sign-in registers or takes an id by hand. */
    public OAuthGrant withoutMetadataDocument(String lastError) {
        return new OAuthGrant(id, tenantId, connectionId, status, resource, issuer, authorizationEndpoint,
                tokenEndpoint, registrationEndpoint, false, issuerParameterRequired, scopesOffered, null, null, null,
                tokenEndpointAuthMethod, redirectUri, state, codeVerifier, returnUrl, startedByUserId, pendingUntil, accessToken,
                refreshToken, expiresAt, scopesGranted, lastError, createdAt, Instant.now());
    }

    public OAuthGrant withPending(String state, String codeVerifier, String returnUrl, @Nullable Long userId,
                                  Instant pendingUntil) {
        return new OAuthGrant(id, tenantId, connectionId, GrantStatus.PENDING, resource, issuer, authorizationEndpoint,
                tokenEndpoint, registrationEndpoint, metadataDocumentSupported, issuerParameterRequired, scopesOffered,
                clientId, clientIdSource, clientSecret, tokenEndpointAuthMethod, redirectUri, state, codeVerifier,
                returnUrl, userId, pendingUntil, accessToken, refreshToken, expiresAt, scopesGranted, lastError,
                createdAt, Instant.now());
    }

    /**
     * The pending sign-in is over: back to what it was before - signed in while the old tokens are there, "sign-in
     * needed" when it was signed in once, else available.
     */
    public OAuthGrant withoutPending(@Nullable String lastError) {
        GrantStatus before = accessToken != null ? GrantStatus.SIGNED_IN
                : scopesGranted != null ? GrantStatus.SIGN_IN_NEEDED : GrantStatus.AVAILABLE;
        return new OAuthGrant(id, tenantId, connectionId, before, resource, issuer, authorizationEndpoint,
                tokenEndpoint, registrationEndpoint, metadataDocumentSupported, issuerParameterRequired, scopesOffered,
                clientId, clientIdSource, clientSecret, tokenEndpointAuthMethod, redirectUri, null, null, null, null,
                null, accessToken, refreshToken, expiresAt, scopesGranted, lastError, createdAt, Instant.now());
    }

    public OAuthGrant withTokens(String accessToken, @Nullable String refreshToken, @Nullable Instant expiresAt,
                                 @Nullable String scopesGranted) {
        return new OAuthGrant(id, tenantId, connectionId, GrantStatus.SIGNED_IN, resource, issuer,
                authorizationEndpoint, tokenEndpoint, registrationEndpoint, metadataDocumentSupported,
                issuerParameterRequired, scopesOffered, clientId, clientIdSource, clientSecret, tokenEndpointAuthMethod,
                redirectUri, state, codeVerifier, returnUrl, startedByUserId, pendingUntil, accessToken, refreshToken,
                expiresAt, scopesGranted, null, createdAt, Instant.now());
    }

    /** The server no longer takes the refresh token: the tokens go, the scopes stay as a sign that it was signed in. */
    public OAuthGrant withSignInNeeded(String lastError) {
        return new OAuthGrant(id, tenantId, connectionId, GrantStatus.SIGN_IN_NEEDED, resource, issuer,
                authorizationEndpoint, tokenEndpoint, registrationEndpoint, metadataDocumentSupported,
                issuerParameterRequired, scopesOffered, clientId, clientIdSource, clientSecret, tokenEndpointAuthMethod,
                redirectUri, state, codeVerifier, returnUrl, startedByUserId, pendingUntil, null, null, null,
                scopesGranted == null ? "" : scopesGranted, lastError, createdAt, Instant.now());
    }

    public OAuthGrant withLastError(@Nullable String lastError) {
        return new OAuthGrant(id, tenantId, connectionId, status, resource, issuer, authorizationEndpoint,
                tokenEndpoint, registrationEndpoint, metadataDocumentSupported, issuerParameterRequired, scopesOffered,
                clientId, clientIdSource, clientSecret, tokenEndpointAuthMethod, redirectUri, state, codeVerifier,
                returnUrl, startedByUserId, pendingUntil, accessToken, refreshToken, expiresAt, scopesGranted,
                lastError, createdAt, Instant.now());
    }
}
