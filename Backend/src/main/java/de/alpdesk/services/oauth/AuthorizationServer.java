package de.alpdesk.services.oauth;

import io.micronaut.core.annotation.Nullable;

/**
 * An authorization server as a connection's software named it - read from its metadata (MCP, an A2A card's metadata
 * URL) or built from the URLs an A2A card lists (then without an issuer). tokenEndpointAuthMethod: the method we will
 * ask for when we register (none when the server offers it).
 */
public record AuthorizationServer(String issuer, String authorizationEndpoint, String tokenEndpoint,
                                  @Nullable String registrationEndpoint, boolean metadataDocumentSupported,
                                  boolean issuerParameterRequired, String tokenEndpointAuthMethod) {

    /**
     * The issuer of a server that named none: stored as it is (oauth_grant.issuer is NOT NULL), and never compared
     * with an iss.
     */
    public static final String NO_ISSUER = "";

    /**
     * A server known only by its two endpoints (an A2A card's authorization code flow): no metadata, so no
     * registration - the client id comes by hand - and no issuer: nothing says what it calls itself, so an iss it
     * sends with the callback has nothing to be compared with.
     */
    public static AuthorizationServer ofEndpoints(String authorizationEndpoint, String tokenEndpoint) {
        return new AuthorizationServer(NO_ISSUER, authorizationEndpoint, tokenEndpoint, null, false, false,
                "client_secret_basic");
    }
}
