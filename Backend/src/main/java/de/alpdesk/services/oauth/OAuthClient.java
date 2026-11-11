package de.alpdesk.services.oauth;

import com.nimbusds.oauth2.sdk.AccessTokenResponse;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationErrorResponse;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.AuthorizationResponse;
import com.nimbusds.oauth2.sdk.GrantType;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.RefreshTokenGrant;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.client.ClientInformation;
import com.nimbusds.oauth2.sdk.client.ClientInformationResponse;
import com.nimbusds.oauth2.sdk.client.ClientMetadata;
import com.nimbusds.oauth2.sdk.client.ClientRegistrationRequest;
import com.nimbusds.oauth2.sdk.client.ClientRegistrationResponse;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.oauth2.sdk.token.Tokens;
import com.nimbusds.oauth2.sdk.util.URLUtils;
import de.alpdesk.dto.oauth.GrantView;
import de.alpdesk.entities.oauth.ClientIdSource;
import de.alpdesk.entities.oauth.GrantStatus;
import de.alpdesk.entities.oauth.OAuthGrant;
import de.alpdesk.repositories.oauth.OAuthGrantRepository;
import de.alpdesk.security.SecretCipher;
import de.alpdesk.services.branding.BrandingService;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.transaction.TransactionOperations;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A connection's sign-in at the authorization server its software named (docs/architecture/mcp.md "Signing in to the
 * software's server") - protocol-neutral: MCP and A2A only say where the server is and send the token as bearer. The
 * flow is OAuth 2.1's authorization code with PKCE, the resource indicator in both requests and the issuer checked on
 * the way back; our client id comes from what we hold, our metadata document, a registration or the tenant admin.
 * The grant row holds the pending sign-in and the tokens, so they survive a restart; a refresh rotates the refresh
 * token under the row's lock, so two calls at once never lose the one valid token.
 */
@Singleton
public class OAuthClient {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthClient.class);
    static final Duration PENDING_FOR = Duration.ofMinutes(10);
    /** A token that expires within this is renewed before the call. */
    static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);
    private static final String DOCUMENT_UNREADABLE = "the server could not read the installation's client document";

    private final OAuthGrantRepository grants;
    private final OAuthHttp http;
    private final OAuthReachability reachability;
    private final OAuthAddresses addresses;
    private final BrandingService brandings;
    private final SecretCipher cipher;
    private final TransactionOperations<Connection> transactions;

    OAuthClient(OAuthGrantRepository grants, OAuthHttp http, OAuthReachability reachability, OAuthAddresses addresses,
                BrandingService brandings, SecretCipher cipher, TransactionOperations<Connection> transactions) {
        this.grants = grants;
        this.http = http;
        this.reachability = reachability;
        this.addresses = addresses;
        this.brandings = brandings;
        this.cipher = cipher;
        this.transactions = transactions;
    }

    /** How completing a sign-in went: signed in, or the error's code. */
    public record Completed(boolean ok, @Nullable String errorCode) {

        static Completed failed(String errorCode) {
            return new Completed(false, errorCode);
        }
    }

    public Optional<OAuthGrant> find(long connectionId) {
        return grants.findByConnectionId(connectionId);
    }

    /**
     * The connection's software asks for a sign-in at this server: its grant is stored, or its endpoints renewed. A
     * client id and tokens stay while the issuer and the token endpoint are the same - they are bound to it; another
     * issuer or token endpoint starts over: the client's secret and the refresh token go to the token endpoint, and an
     * A2A card naming the URLs alone could otherwise send them to a host of its choosing. Nothing is sent.
     */
    @Transactional
    public OAuthGrant prepare(long tenantId, long connectionId, String resource, AuthorizationServer server,
                              @Nullable String scopesOffered) {
        Optional<OAuthGrant> existing = grants.findByConnectionIdForUpdate(connectionId);
        if (existing.isPresent() && existing.get().issuer().equals(server.issuer())
                && existing.get().tokenEndpoint().equals(server.tokenEndpoint())) {
            return grants.update(existing.get().withServer(resource, server.authorizationEndpoint(),
                    server.tokenEndpoint(), server.registrationEndpoint(), server.metadataDocumentSupported(),
                    server.issuerParameterRequired(), scopesOffered, server.tokenEndpointAuthMethod()));
        }
        existing.ifPresent(grants::delete);
        Instant now = Instant.now();
        return grants.save(new OAuthGrant(null, tenantId, connectionId, GrantStatus.AVAILABLE, resource,
                server.issuer(), server.authorizationEndpoint(), server.tokenEndpoint(), server.registrationEndpoint(),
                server.metadataDocumentSupported(), server.issuerParameterRequired(), scopesOffered, null, null, null,
                server.tokenEndpointAuthMethod(), null, null, null, null, null, null, null, null, null, null, null,
                now, now));
    }

    /**
     * Starts a sign-in: obtains a client id when the grant has none (our metadata document when the server reads one
     * and our address is public, else a registration, else 409 - the tenant admin enters one), stores the pending
     * state and verifier, and returns the server's sign-in page to send the browser to - a web address only, checked
     * before anything else: a server whose page we would not open is not registered at either. Not in a transaction:
     * a registration is a call to the server.
     */
    public URI startSignIn(long connectionId, String connectionName, String ownOrigin, String returnUrl,
                           @Nullable Long userId) {
        OAuthGrant grant = grants.findByConnectionId(connectionId).orElseThrow(() -> notAvailable(connectionName));
        URI signInPage = http.signInPage(grant.authorizationEndpoint(), connectionName);
        String callback = addresses.callback(ownOrigin);
        grant = withUsableClient(grant, connectionName, ownOrigin, callback);
        State state = new State();
        CodeVerifier verifier = new CodeVerifier();
        OAuthGrant client = grant;
        OAuthGrant pending = transactions.executeWrite(_ -> {
            // Only the grant the client was obtained for: one replaced meanwhile (another address) has another server.
            OAuthGrant current = grants.findByConnectionIdForUpdate(connectionId)
                    .filter(found -> found.id().equals(client.id()))
                    .orElseThrow(() -> notAvailable(connectionName));
            return grants.update(current.withClient(client.clientId(), client.clientIdSource(), client.clientSecret(),
                            client.tokenEndpointAuthMethod(), callback)
                    .withPending(state.getValue(), cipher.encrypt(verifier.getValue()), returnUrl, userId,
                            Instant.now().plus(PENDING_FOR)));
        });
        AuthorizationRequest.Builder request = new AuthorizationRequest.Builder(ResponseType.CODE,
                new ClientID(pending.clientId()))
                .endpointURI(signInPage)
                .redirectionURI(URI.create(callback))
                .codeChallenge(verifier, CodeChallengeMethod.S256)
                .state(state)
                .resource(URI.create(pending.resource()));
        if (pending.scopesOffered() != null && !pending.scopesOffered().isBlank()) {
            request.scope(Scope.parse(pending.scopesOffered()));
        }
        return request.build().toURI();
    }

    /**
     * The grant with a client id for this callback: the one held (unless it was made for another callback address,
     * or our document was left unread), else our metadata document, a registration - or 409 when the server takes
     * neither and none was entered.
     */
    private OAuthGrant withUsableClient(OAuthGrant grant, String connectionName, String ownOrigin, String callback) {
        // Only for a grant without tokens: a signed-in grant's tokens belong to its client id - a sign-in left
        // unfinished over it says nothing about our document.
        if (grant.status() == GrantStatus.PENDING && grant.clientIdSource() == ClientIdSource.METADATA_DOCUMENT
                && grant.accessToken() == null && grant.pendingUntil() != null
                && grant.pendingUntil().isBefore(Instant.now())) {
            grant = save(grant.withoutMetadataDocument(DOCUMENT_UNREADABLE));
        }
        boolean madeForAnotherCallback = grant.clientIdSource() != null && grant.clientIdSource() != ClientIdSource.ENTERED
                && !callback.equals(grant.redirectUri());
        if (grant.clientId() != null && !madeForAnotherCallback) {
            return grant;
        }
        if (grant.metadataDocumentSupported() && reachability.isPublic(URI.create(ownOrigin).getHost())) {
            // A public client: the method stays the one a registration would ask for, should the document fail.
            return grant.withClient(addresses.metadataDocument(ownOrigin), ClientIdSource.METADATA_DOCUMENT, null,
                    grant.tokenEndpointAuthMethod(), callback);
        }
        if (grant.registrationEndpoint() != null) {
            return register(grant, connectionName, callback);
        }
        save(grant.withClient(null, null, null, grant.tokenEndpointAuthMethod(), callback)
                .withLastError("the server needs a client id - enter it in the connection"));
        throw ServiceException.conflict("oauth.registrationNeeded", connectionName
                + ": its sign-in server needs a client id - register the installation at the server's portal and "
                + "enter it in the connection", Map.of("name", connectionName));
    }

    /** Dynamic client registration (RFC 7591) - a public client when the server offers "none". */
    private OAuthGrant register(OAuthGrant grant, String connectionName, String callback) {
        ClientMetadata metadata = new ClientMetadata();
        metadata.setName(brandings.installation().name());
        metadata.setRedirectionURI(URI.create(callback));
        metadata.setGrantTypes(Set.of(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN));
        metadata.setResponseTypes(Set.of(ResponseType.CODE));
        metadata.setTokenEndpointAuthMethod(ClientAuthenticationMethod.parse(grant.tokenEndpointAuthMethod()));
        if (grant.scopesOffered() != null && !grant.scopesOffered().isBlank()) {
            metadata.setScope(Scope.parse(grant.scopesOffered()));
        }
        metadata.setCustomField("application_type", "web");
        HTTPResponse response = http.send(new ClientRegistrationRequest(URI.create(grant.registrationEndpoint()),
                metadata, null).toHTTPRequest(), connectionName);
        ClientInformation client;
        try {
            ClientRegistrationResponse parsed = ClientRegistrationResponse.parse(response);
            if (!(parsed instanceof ClientInformationResponse information)) {
                throw registrationRefused(connectionName);
            }
            client = information.getClientInformation();
        } catch (ParseException e) {
            throw registrationRefused(connectionName);
        }
        String secret = client.getSecret() == null ? null : client.getSecret().getValue();
        ClientAuthenticationMethod method = client.getMetadata().getTokenEndpointAuthMethod();
        String methodName = method != null ? method.getValue()
                : secret == null ? ClientAuthenticationMethod.NONE.getValue() : grant.tokenEndpointAuthMethod();
        return grant.withClient(client.getID().getValue(), ClientIdSource.REGISTERED,
                secret == null ? null : cipher.encrypt(secret), methodName, callback);
    }

    /** A client id the tenant admin took from the server's portal - with a secret, sent as basic authentication. */
    @Transactional
    public void enterClient(long connectionId, String connectionName, String clientId, @Nullable String clientSecret) {
        OAuthGrant grant = grants.findByConnectionIdForUpdate(connectionId).orElseThrow(() -> notAvailable(connectionName));
        boolean withSecret = clientSecret != null && !clientSecret.isBlank();
        grants.update(grant.withClient(clientId.strip(), ClientIdSource.ENTERED,
                withSecret ? cipher.encrypt(clientSecret.strip()) : null,
                withSecret ? ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue()
                        : ClientAuthenticationMethod.NONE.getValue(), grant.redirectUri()));
    }

    /** Where the browser goes back to with a callback's answer: the page the sign-in started on, and its connection. */
    public record Returning(long connectionId, String returnUrl) {
    }

    /**
     * The anonymous callback's only question: the page of the pending sign-in this answer's state belongs to - empty
     * for a state nobody started. Nothing is changed: the answer is completed by the person who started the sign-in,
     * signed in on that page ({@link #complete}) - an answer someone else's browser brings back signs in no one.
     */
    public Optional<Returning> returning(@Nullable String query) {
        List<String> states = query == null ? null : URLUtils.parseParameters(query).get("state");
        if (states == null || states.isEmpty() || states.getFirst() == null || states.getFirst().isBlank()) {
            return Optional.empty();
        }
        return grants.findByState(states.getFirst()).filter(grant -> grant.returnUrl() != null)
                .map(grant -> new Returning(grant.connectionId(), grant.returnUrl()));
    }

    /**
     * Completes a sign-in with the server's answer the browser brought back - for the person who started it only: the
     * pending grant is found by its state - once -, and must be this tenant's and connection's, started by this user
     * (null: the admin); for anyone else it is unknown and stays as it is. Then the issuer is checked (RFC 9207) and
     * the code exchanged for tokens. A refused or expired sign-in puts the grant back where it was; the server's own
     * error texts are never shown or stored. The exchange is sent outside a transaction.
     */
    public Completed complete(URI callbackUri, long tenantId, long connectionId, @Nullable Long userId) {
        AuthorizationResponse response;
        try {
            response = AuthorizationResponse.parse(callbackUri);
        } catch (ParseException e) {
            return Completed.failed("oauth.stateUnknown");
        }
        if (response.getState() == null) {
            return Completed.failed("oauth.stateUnknown");
        }
        Callback callback = transactions.executeWrite(_ -> takePending(response, tenantId, connectionId, userId));
        if (callback.completed() != null) {
            return callback.completed();
        }
        OAuthGrant grant = callback.grant();
        String connectionName = "connection " + grant.connectionId();
        AuthorizationGrant codeGrant = new AuthorizationCodeGrant(
                response.toSuccessResponse().getAuthorizationCode(), URI.create(grant.redirectUri()),
                new CodeVerifier(callback.verifier()));
        TokenOutcome outcome;
        try {
            outcome = tokens(grant, codeGrant, connectionName);
        } catch (ServiceException e) {
            outcome = new TokenOutcome(null, "the token could not be fetched: " + e.code(), false);
        }
        TokenOutcome result = outcome;
        transactions.executeWrite(_ -> {
            // Only the grant the code was for: one replaced meanwhile (another address) must not get these tokens.
            grants.findByConnectionIdForUpdate(grant.connectionId()).filter(current -> current.id().equals(grant.id()))
                    .ifPresent(current -> grants.update(result.tokens() != null
                            ? withTokens(current, result.tokens(), current.scopesOffered())
                            : current.withLastError(result.error())));
            return null;
        });
        return result.tokens() != null ? new Completed(true, null) : Completed.failed("oauth.exchangeFailed");
    }

    /** The pending grant of a callback, taken under its lock: either what is left to exchange, or the outcome. */
    private record Callback(@Nullable OAuthGrant grant, @Nullable String verifier, @Nullable Completed completed) {
    }

    private Callback takePending(AuthorizationResponse response, long tenantId, long connectionId,
                                 @Nullable Long userId) {
        // The state and who started it count, not the grant's status: a refresh refused meanwhile may have marked the
        // grant "sign-in needed" - the sign-in under way is what repairs it.
        Optional<OAuthGrant> found = grants.findByStateForUpdate(response.getState().getValue())
                .filter(grant -> grant.tenantId() == tenantId && grant.connectionId() == connectionId
                        && Objects.equals(grant.startedByUserId(), userId));
        if (found.isEmpty()) {
            return new Callback(null, null, Completed.failed("oauth.stateUnknown"));
        }
        OAuthGrant grant = found.get();
        if (grant.pendingUntil() == null || grant.pendingUntil().isBefore(Instant.now())) {
            grants.update(grant.withoutPending("the sign-in took too long - start it again"));
            return new Callback(null, null, Completed.failed("oauth.stateExpired"));
        }
        String issuer = response.getIssuer() == null ? null : response.getIssuer().getValue();
        // A server known by its endpoints alone named no issuer: an iss it sends has nothing to be compared with.
        boolean issuerKnown = !AuthorizationServer.NO_ISSUER.equals(grant.issuer());
        boolean anotherIssuer = issuer != null && issuerKnown && !issuer.equals(grant.issuer());
        if ((grant.issuerParameterRequired() && issuer == null) || anotherIssuer) {
            grants.update(grant.withoutPending("the answer of the sign-in came from another server"));
            return failed("oauth.issuerMismatch");
        }
        if (!response.indicatesSuccess()) {
            return new Callback(null, null, refused(grant, response.toErrorResponse()));
        }
        if (response.toSuccessResponse().getAuthorizationCode() == null) {
            grants.update(grant.withoutPending("the sign-in server sent no code"));
            return failed("oauth.exchangeFailed");
        }
        String verifier = cipher.decrypt(grant.codeVerifier());
        grants.update(grant.withoutPending(null));
        return new Callback(grant, verifier, null);
    }

    /** The server refused the sign-in: back to where it was - and our document left out when it could not read it. */
    private Completed refused(OAuthGrant grant, AuthorizationErrorResponse error) {
        String code = error.getErrorObject() == null ? null : error.getErrorObject().getCode();
        OAuthGrant back;
        if (OAuth2Error.ACCESS_DENIED_CODE.equals(code)) {
            back = grant.withoutPending("the sign-in was refused at the server");
        } else if (grant.clientIdSource() == ClientIdSource.METADATA_DOCUMENT && grant.accessToken() == null) {
            // Signed in through our document before, the grant keeps it: its tokens belong to that client id.
            back = grant.withoutPending(null).withoutMetadataDocument(DOCUMENT_UNREADABLE);
        } else {
            back = grant.withoutPending("the server refused the sign-in (" + safeCode(code) + ")");
        }
        grants.update(back);
        return Completed.failed("oauth.denied");
    }

    private static Callback failed(String errorCode) {
        return new Callback(null, null, Completed.failed(errorCode));
    }

    /**
     * The access token for a call: renewed first when it expires within {@link #REFRESH_MARGIN}. 502
     * oauth.signInNeeded when there is none.
     */
    public String accessToken(long connectionId, String connectionName, String resource) {
        OAuthGrant grant = grants.findByConnectionId(connectionId).orElseThrow(() -> signInNeeded(connectionName));
        if (grant.accessToken() == null || !grant.resource().equals(resource)) {
            throw signInNeeded(connectionName);
        }
        String token;
        try {
            token = cipher.decrypt(grant.accessToken());
        } catch (IllegalStateException e) {
            // APP_SECRET_KEY changed: the tokens are lost - the connection says so instead of failing every answer.
            signInLost(connectionId, "its tokens can no longer be read - sign in again");
            throw signInNeeded(connectionName);
        }
        if (grant.expiresAt() != null && grant.expiresAt().isBefore(Instant.now().plus(REFRESH_MARGIN))) {
            return refresh(connectionId, connectionName, resource, token);
        }
        return token;
    }

    /**
     * A new access token with the refresh token, under the grant's lock: when the stored token is no longer the one
     * that failed, another call renewed it meanwhile - that one is taken. A rotated refresh token is stored with it.
     * A refused refresh (400/401 - invalid_grant) marks the grant "sign-in needed" (502 oauth.signInNeeded); a server
     * that cannot be reached leaves the grant as it is (502 oauth.refreshFailed). Holds the row lock during the call
     * on purpose - the token endpoint answers within seconds, and a second refresh would spend the rotated token.
     */
    public String refresh(long connectionId, String connectionName, String resource, @Nullable String failedToken) {
        Refreshed refreshed = transactions.executeWrite(_ -> {
            OAuthGrant grant = grants.findByConnectionIdForUpdate(connectionId).orElse(null);
            if (grant == null || grant.accessToken() == null || !grant.resource().equals(resource)) {
                return new Refreshed(null, signInNeeded(connectionName));
            }
            String stored = cipher.decrypt(grant.accessToken());
            if (failedToken != null && !stored.equals(failedToken)) {
                return new Refreshed(stored, null);
            }
            if (grant.refreshToken() == null) {
                grants.update(grant.withSignInNeeded("the server gave no refresh token - sign in again"));
                return new Refreshed(null, signInNeeded(connectionName));
            }
            TokenOutcome outcome = tokens(grant,
                    new RefreshTokenGrant(new RefreshToken(cipher.decrypt(grant.refreshToken()))), connectionName);
            if (outcome.tokens() != null) {
                withTokens(grant, outcome.tokens(), grant.scopesGranted());
                return new Refreshed(outcome.tokens().getAccessToken().getValue(), null);
            }
            if (outcome.refused()) {
                LOG.info("The sign-in of connection {} needs to be repeated: {}", connectionId, outcome.error());
                grants.update(grant.withSignInNeeded(outcome.error()));
                return new Refreshed(null, signInNeeded(connectionName));
            }
            return new Refreshed(null, OAuthHttp.gateway("oauth.refreshFailed", connectionName,
                    "its sign-in could not be renewed - the sign-in server did not answer as expected"));
        });
        if (refreshed.failure() != null) {
            throw refreshed.failure();
        }
        return refreshed.token();
    }

    private record Refreshed(@Nullable String token, @Nullable ServiceException failure) {
    }

    /** The answer of the token endpoint: the tokens, or why not - refused: the server said no (400/401). */
    private record TokenOutcome(@Nullable Tokens tokens, @Nullable String error, boolean refused) {
    }

    /** A token request with the resource indicator, the client authenticated as its registration says. */
    private TokenOutcome tokens(OAuthGrant grant, AuthorizationGrant authorizationGrant, String connectionName) {
        if (grant.clientId() == null) {
            // Its tokens were given to a client id the grant no longer holds - only a new sign-in brings both.
            return new TokenOutcome(null, "the installation's client id at the server is gone - sign in again", true);
        }
        URI endpoint = URI.create(grant.tokenEndpoint());
        String method = grant.tokenEndpointAuthMethod();
        ClientID clientId = new ClientID(grant.clientId());
        TokenRequest.Builder builder;
        if (grant.clientSecret() == null || ClientAuthenticationMethod.NONE.getValue().equals(method)) {
            builder = new TokenRequest.Builder(endpoint, clientId, authorizationGrant);
        } else if (ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue().equals(method)) {
            builder = new TokenRequest.Builder(endpoint,
                    new ClientSecretPost(clientId, new Secret(cipher.decrypt(grant.clientSecret()))), authorizationGrant);
        } else {
            builder = new TokenRequest.Builder(endpoint,
                    new ClientSecretBasic(clientId, new Secret(cipher.decrypt(grant.clientSecret()))), authorizationGrant);
        }
        HTTPResponse response = http.send(builder.resource(URI.create(grant.resource())).build().toHTTPRequest(),
                connectionName);
        boolean refusedStatus = response.getStatusCode() == 400 || response.getStatusCode() == 401;
        try {
            TokenResponse parsed = TokenResponse.parse(response);
            if (parsed instanceof AccessTokenResponse success) {
                return new TokenOutcome(success.getTokens(), null, false);
            }
            String code = parsed.toErrorResponse().getErrorObject() == null ? null
                    : parsed.toErrorResponse().getErrorObject().getCode();
            return new TokenOutcome(null, "the sign-in server refused the token (" + safeCode(code) + ")",
                    refusedStatus || OAuth2Error.INVALID_GRANT_CODE.equals(code));
        } catch (ParseException e) {
            return new TokenOutcome(null, "the sign-in server answered with HTTP " + response.getStatusCode(),
                    refusedStatus);
        }
    }

    /** The tokens stored encrypted - a refresh token only when the answer has one (rotation), else the old stays. */
    private OAuthGrant withTokens(OAuthGrant grant, Tokens tokens, @Nullable String scopesRequested) {
        long lifetime = tokens.getAccessToken().getLifetime();
        Scope scope = tokens.getAccessToken().getScope();
        RefreshToken refreshToken = tokens.getRefreshToken();
        return grants.update(grant.withTokens(cipher.encrypt(tokens.getAccessToken().getValue()),
                refreshToken != null ? cipher.encrypt(refreshToken.getValue()) : grant.refreshToken(),
                lifetime > 0 ? Instant.now().plusSeconds(lifetime) : null,
                scope != null ? scope.toString() : scopesRequested));
    }

    /**
     * The server refused a token just renewed: its tokens no longer count (the client or the grant was revoked there) -
     * "sign-in needed", as after a refused refresh.
     */
    @Transactional
    public void signInLost(long connectionId, String reason) {
        grants.findByConnectionIdForUpdate(connectionId).filter(grant -> grant.accessToken() != null).ifPresent(grant -> {
            LOG.info("The sign-in of connection {} needs to be repeated: {}", connectionId, reason);
            grants.update(grant.withSignInNeeded(reason));
        });
    }

    /** Removes the sign-in - a key replaces it, the address changed, or a tenant admin removed it. */
    @Transactional
    public void forget(long connectionId) {
        grants.deleteByConnectionId(connectionId);
    }

    /** What the tenant admin sees of a grant - never a token, a secret or the verifier. */
    public static GrantView view(OAuthGrant grant) {
        boolean clientIdNeeded = grant.clientIdSource() == ClientIdSource.ENTERED
                || (grant.clientId() == null && grant.registrationEndpoint() == null && grant.redirectUri() != null);
        // A sign-in nobody finished in its time is over: shown as what the grant goes back to.
        boolean abandoned = grant.status() == GrantStatus.PENDING && grant.pendingUntil() != null
                && grant.pendingUntil().isBefore(Instant.now());
        GrantStatus status = abandoned ? grant.withoutPending(null).status() : grant.status();
        return new GrantView(status.name(), scopes(grant.scopesGranted()), scopes(grant.scopesOffered()),
                grant.expiresAt(), grant.lastError(), host(grant),
                grant.clientIdSource() == null ? null : grant.clientIdSource().name(), grant.redirectUri(),
                clientIdNeeded);
    }

    private static List<String> scopes(@Nullable String scopes) {
        return scopes == null || scopes.isBlank() ? List.of() : Arrays.asList(scopes.strip().split("\\s+"));
    }

    /** The sign-in server's host, to show - of its issuer, or of its sign-in page where it named none. */
    private static String host(OAuthGrant grant) {
        String address = AuthorizationServer.NO_ISSUER.equals(grant.issuer()) ? grant.authorizationEndpoint()
                : grant.issuer();
        try {
            String host = URI.create(address).getHost();
            return host == null ? address : host;
        } catch (IllegalArgumentException e) {
            return address;
        }
    }

    private OAuthGrant save(OAuthGrant grant) {
        return transactions.executeWrite(_ -> grants.update(grant));
    }

    /** An error code of the server as a short word only - its own texts are never shown or stored. */
    private static String safeCode(@Nullable String code) {
        return code == null || !code.matches("[a-z_]{1,40}") ? "unknown" : code;
    }

    private static ServiceException registrationRefused(String connectionName) {
        return OAuthHttp.gateway("oauth.registrationRefused", connectionName,
                "its sign-in server refused to register the installation");
    }

    public static ServiceException signInNeeded(String connectionName) {
        return OAuthHttp.gateway("oauth.signInNeeded", connectionName,
                "needs a sign-in - sign in on the page Software");
    }

    private static ServiceException notAvailable(String connectionName) {
        return ServiceException.conflict("oauth.notAvailable", connectionName
                + ": its server asks for no sign-in", Map.of("name", connectionName));
    }
}
