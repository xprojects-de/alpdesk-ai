package de.alpdesk.services.oauth;

import com.nimbusds.oauth2.sdk.GrantType;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.as.AuthorizationServerMetadata;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.minidev.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads what an authorization server and a protected resource publish about themselves (docs/architecture/mcp.md
 * "Signing in to the software's server"): the server's metadata (RFC 8414 and OpenID, in the MCP specification's
 * order, parsed by Nimbus) and the resource's metadata (RFC 9728 - Nimbus has none, a few lines of JSON).
 */
@Singleton
public class OAuthMetadata {

    private final OAuthHttp http;

    OAuthMetadata(OAuthHttp http) {
        this.http = http;
    }

    /** What a protected resource names: the first authorization server and the scopes it supports (null: none). */
    public record ProtectedResource(String authorizationServer, @Nullable String scopesSupported) {
    }

    /**
     * The authorization server's metadata, tried at the specification's addresses in its order - the document's issuer
     * must be the one asked for. 502 when none can be read, when it offers no authorization code or no PKCE with S256.
     */
    public AuthorizationServer read(String issuer, String connectionName) {
        AuthorizationServerMetadata metadata = null;
        for (String address : metadataAddresses(issuer)) {
            metadata = metadataAt(address, connectionName);
            if (metadata != null) {
                break;
            }
        }
        if (metadata == null || !issuer.equals(metadata.getIssuer().getValue())) {
            throw unreadable(connectionName);
        }
        return usable(metadata, issuer, connectionName);
    }

    /**
     * The metadata at the address an A2A card names: its issuer is the one whose metadata addresses include it - a
     * document at an address its issuer does not own is not taken. 502 as at {@link #read}.
     */
    public AuthorizationServer readAt(String url, String connectionName) {
        AuthorizationServerMetadata metadata = metadataAt(url, connectionName);
        if (metadata == null || !metadataAddresses(metadata.getIssuer().getValue()).contains(url)) {
            throw unreadable(connectionName);
        }
        return usable(metadata, metadata.getIssuer().getValue(), connectionName);
    }

    public static ServiceException noAuthorizationCode(String connectionName) {
        return OAuthHttp.gateway("oauth.noAuthorizationCode", connectionName,
                "its sign-in server offers no sign-in in the browser (authorization code)");
    }

    private static ServiceException unreadable(String connectionName) {
        return OAuthHttp.gateway("oauth.metadataUnreadable", connectionName,
                "its sign-in server's description could not be read");
    }

    private static AuthorizationServer usable(AuthorizationServerMetadata metadata, String issuer, String connectionName) {
        // RFC 8414: without grant_types_supported a server offers the authorization code (and implicit).
        if (metadata.getGrantTypes() != null && !metadata.getGrantTypes().contains(GrantType.AUTHORIZATION_CODE)) {
            throw noAuthorizationCode(connectionName);
        }
        if (metadata.getCodeChallengeMethods() == null
                || !metadata.getCodeChallengeMethods().contains(CodeChallengeMethod.S256)) {
            throw OAuthHttp.gateway("oauth.pkceUnsupported", connectionName,
                    "its sign-in server does not support PKCE with S256");
        }
        if (metadata.getAuthorizationEndpointURI() == null || metadata.getTokenEndpointURI() == null) {
            throw unreadable(connectionName);
        }
        return new AuthorizationServer(issuer, metadata.getAuthorizationEndpointURI().toString(),
                metadata.getTokenEndpointURI().toString(),
                metadata.getRegistrationEndpointURI() == null ? null : metadata.getRegistrationEndpointURI().toString(),
                Boolean.TRUE.equals(metadata.getCustomParameter("client_id_metadata_document_supported")),
                metadata.supportsAuthorizationResponseIssuerParam(),
                authMethod(metadata.getTokenEndpointAuthMethods()));
    }

    /**
     * The resource metadata at this address (RFC 9728) - null when there is none, it cannot be read or names no
     * authorization server.
     */
    @Nullable
    public ProtectedResource protectedResource(String url, String connectionName) {
        OAuthHttp.Answer answer = http.get(url, connectionName);
        if (answer.status() != 200) {
            return null;
        }
        try {
            JSONObject json = JSONObjectUtils.parse(answer.body());
            List<String> servers = JSONObjectUtils.getStringList(json, "authorization_servers", List.of());
            if (servers.isEmpty()) {
                return null;
            }
            List<String> scopes = JSONObjectUtils.getStringList(json, "scopes_supported", List.of());
            return new ProtectedResource(servers.getFirst(), scopes.isEmpty() ? null : String.join(" ", scopes));
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * RFC 8414's address with the issuer's path appended, then OpenID's the same way, then OpenID's below the path; for
     * an issuer without a path the first two.
     */
    static List<String> metadataAddresses(String issuer) {
        URI uri = URI.create(issuer);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        List<String> addresses = new ArrayList<>();
        addresses.add(origin + "/.well-known/oauth-authorization-server" + path);
        addresses.add(origin + "/.well-known/openid-configuration" + path);
        if (!path.isEmpty()) {
            addresses.add(origin + path + "/.well-known/openid-configuration");
        }
        return addresses;
    }

    @Nullable
    private AuthorizationServerMetadata metadataAt(String address, String connectionName) {
        OAuthHttp.Answer answer = http.get(address, connectionName);
        if (answer.status() != 200) {
            return null;
        }
        try {
            return AuthorizationServerMetadata.parse(answer.body());
        } catch (ParseException | RuntimeException e) {
            return null;
        }
    }

    /** none when the server offers it; else a secret - basic unless only post is offered (RFC 8414: basic by default). */
    private static String authMethod(@Nullable List<ClientAuthenticationMethod> offered) {
        if (offered == null) {
            return ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue();
        }
        if (offered.contains(ClientAuthenticationMethod.NONE)) {
            return ClientAuthenticationMethod.NONE.getValue();
        }
        if (!offered.contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                && offered.contains(ClientAuthenticationMethod.CLIENT_SECRET_POST)) {
            return ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue();
        }
        return ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue();
    }
}
