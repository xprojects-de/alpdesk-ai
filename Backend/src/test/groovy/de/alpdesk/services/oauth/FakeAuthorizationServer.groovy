package de.alpdesk.services.oauth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micronaut.serde.ObjectMapper

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * An OAuth 2.1 authorization server for the specs, in the JVM - never a real one. Its fields are the shapes a real
 * server takes: where its metadata is (RFC 8414 or OpenID, below or above the issuer's path), whether it registers
 * clients (RFC 7591) or reads a client id metadata document (CIMD), which client authentications it takes, whether it
 * sends iss with the callback (RFC 9207), whether refresh tokens rotate. It checks what a strict server checks - PKCE
 * with S256, the redirect address, the resource indicator in both requests, a code used once, the client's secret -
 * and records every request ({@code received}). {@code issuedTokens} are the valid access tokens the fake software
 * accepts as bearer.
 */
class FakeAuthorizationServer {

    final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    /** Every request: [path, method, params (query and form), authorization]. */
    final List<Map> received = new CopyOnWriteArrayList<>()
    /** The valid access tokens. */
    final Set<String> issuedTokens = ConcurrentHashMap.newKeySet()
    /** The refresh tokens it still takes. */
    final Set<String> refreshTokens = ConcurrentHashMap.newKeySet()
    /** The registered clients by id: [secret, method, redirectUris, request]. */
    final Map<String, Map> clients = new ConcurrentHashMap<>()
    final AtomicInteger refreshes = new AtomicInteger()
    final AtomicInteger documentFetches = new AtomicInteger()
    private final Map<String, Map> codes = new ConcurrentHashMap<>()
    private final AtomicInteger counter = new AtomicInteger()

    /** The issuer's path ("" - the origin alone, "/mcp", "/tenant1"). */
    volatile String issuerPath = ""
    /** Where the metadata is: "oauth" (RFC 8414), "openid" (OpenID with the path appended) or "openid-below" (below the path). */
    volatile String metadataAt = "oauth"
    /** The issuer the metadata document names; null: its own. */
    volatile String announcedIssuer = null
    volatile List<String> scopesSupported = ["read", "write"]
    volatile List<String> authMethods = ["none", "client_secret_basic", "client_secret_post"]
    volatile List<String> grantTypes = ["authorization_code", "refresh_token"]
    volatile List<String> challengeMethods = ["S256"]
    volatile boolean withRegistration = true
    volatile boolean refuseRegistration = false
    /** Announces client_id_metadata_document_supported and fetches a client id that is a URL at /authorize. */
    volatile boolean cimd = false
    /** The document cannot be fetched or is not accepted - the sign-in is refused with an error. */
    volatile boolean cimdFetchFails = false
    /** Announces and sends iss with the callback. */
    volatile boolean issParameter = false
    /** Sends another issuer as iss. */
    volatile boolean wrongIss = false
    /** Announces iss but does not send it. */
    volatile boolean issMissing = false
    /** The person refuses at the consent page. */
    volatile boolean deny = false
    /** The scopes it grants; null: the ones asked for. */
    volatile String grantScopes = null
    volatile long tokenLifetime = 3600
    volatile boolean rotate = true
    volatile boolean refuseRefresh = false
    volatile long slowRefreshMillis = 0
    /** The token endpoint answers 500. */
    volatile boolean tokenEndpointFails = false
    /** The exchange of a code is refused (invalid_grant). */
    volatile boolean refuseExchange = false
    /** The endpoints the metadata names instead of its own; null: its own. */
    volatile String authorizationEndpoint = null
    volatile String tokenEndpoint = null
    volatile String registrationEndpoint = null
    /** Fields the metadata leaves out. */
    volatile List<String> withoutFields = []
    /** GET /stall sends its headers and then nothing for this long. */
    volatile long stallMillis = 0
    /** Where /redirect sends a caller on (302). */
    volatile String redirectTo = null
    /** Runs while a code is exchanged, before the tokens are answered - what happens to the grant meanwhile. */
    volatile Closure duringExchange = null

    FakeAuthorizationServer() {
        http.createContext("/") { HttpExchange exchange -> handle(exchange) }
        http.start()
    }

    String origin() {
        "http://127.0.0.1:${http.address.port}"
    }

    String issuer() {
        origin() + issuerPath
    }

    void stop() {
        http.stop(0)
    }

    /** A client registered by hand at the server's portal. */
    void portalClient(String id, String secret, String redirectUri) {
        clients[id] = [secret: secret, method: secret ? "client_secret_basic" : "none", redirectUris: [redirectUri]]
    }

    void revoke(String token) {
        issuedTokens.remove(token)
    }

    boolean valid(String authorization) {
        authorization?.startsWith("Bearer ") && issuedTokens.contains(authorization.substring(7))
    }

    List<Map> requests(String path) {
        received.findAll { it.path == path }
    }

    Map metadata() {
        Map metadata = [issuer: announcedIssuer ?: issuer(),
                        authorization_endpoint: authorizationEndpoint ?: origin() + "/authorize",
                        token_endpoint: tokenEndpoint ?: origin() + "/token",
                        response_types_supported: ["code"],
                        grant_types_supported: grantTypes,
                        code_challenge_methods_supported: challengeMethods,
                        token_endpoint_auth_methods_supported: authMethods,
                        scopes_supported: scopesSupported]
        if (withRegistration) {
            metadata.registration_endpoint = registrationEndpoint ?: origin() + "/register"
        }
        if (cimd) {
            metadata.client_id_metadata_document_supported = true
        }
        if (issParameter || issMissing) {
            metadata.authorization_response_iss_parameter_supported = true
        }
        metadata.keySet().removeAll(withoutFields)
        metadata
    }

    private void handle(HttpExchange exchange) {
        String path = exchange.requestURI.path
        String body = exchange.requestBody.getText("UTF-8")
        Map<String, String> params = form(exchange.requestURI.rawQuery)
        if (exchange.requestHeaders.getFirst("Content-Type")?.startsWith("application/x-www-form-urlencoded")) {
            params += form(body)
        }
        received << [path: path, method: exchange.requestMethod, params: params, body: body,
                     authorization: exchange.requestHeaders.getFirst("Authorization")]
        if (path == metadataPath()) {
            json(exchange, 200, metadata())
        } else if (path == "/register" && withRegistration) {
            register(exchange, ObjectMapper.getDefault().readValue(body, Map))
        } else if (path == "/authorize") {
            authorize(exchange, params)
        } else if (path == "/token") {
            token(exchange, params)
        } else if (path == "/stall") {
            stall(exchange)
        } else if (path == "/redirect") {
            exchange.responseHeaders.add("Location", redirectTo)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        } else if (path == "/large") {
            json(exchange, 200, [padding: "x" * 70_000])
        } else {
            json(exchange, 404, [error: "not_found"])
        }
    }

    /** The headers at once, the body never within the time: what a request's own timeout does not cover. */
    private void stall(HttpExchange exchange) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.flush()
        Thread.sleep(stallMillis)
        exchange.close()
    }

    private String metadataPath() {
        switch (metadataAt) {
            case "openid": return "/.well-known/openid-configuration" + issuerPath
            case "openid-below": return issuerPath + "/.well-known/openid-configuration"
            case "nowhere": return "/no-metadata"
            default: return "/.well-known/oauth-authorization-server" + issuerPath
        }
    }

    private void register(HttpExchange exchange, Map request) {
        if (refuseRegistration) {
            json(exchange, 400, [error: "invalid_client_metadata", error_description: "SECRET-REASON"])
            return
        }
        String method = request.token_endpoint_auth_method ?: "client_secret_basic"
        String id = "client-" + counter.incrementAndGet()
        String secret = method == "none" ? null : "secret-" + counter.incrementAndGet()
        clients[id] = [secret: secret, method: method, redirectUris: request.redirect_uris, request: request]
        Map answer = [client_id: id, token_endpoint_auth_method: method, redirect_uris: request.redirect_uris,
                      grant_types: request.grant_types, client_name: request.client_name]
        if (secret) {
            answer.client_secret = secret
        }
        json(exchange, 201, answer)
    }

    private void authorize(HttpExchange exchange, Map<String, String> params) {
        String redirect = params.redirect_uri
        Map client = clientFor(params.client_id, redirect)
        if (client == null || !(redirect in client.redirectUris)) {
            if (client == null && params.client_id?.startsWith("http") && redirect) {
                back(exchange, redirect, [error: "invalid_client", error_description: "SECRET-REASON", state: params.state])
                return
            }
            json(exchange, 400, [error: "invalid_client"])
            return
        }
        if (params.code_challenge_method != "S256" || !params.code_challenge || !params.resource) {
            back(exchange, redirect, [error: "invalid_request", state: params.state])
            return
        }
        if (deny) {
            back(exchange, redirect, [error: "access_denied", error_description: "SECRET-DESCRIPTION", state: params.state])
            return
        }
        String code = "code-" + counter.incrementAndGet()
        codes[code] = [clientId: params.client_id, redirectUri: redirect, challenge: params.code_challenge,
                       resource: params.resource, scope: grantScopes ?: params.scope]
        Map answer = [code: code, state: params.state]
        if (issParameter && !issMissing) {
            answer.iss = wrongIss ? "http://127.0.0.1:1/other" : issuer()
        }
        back(exchange, redirect, answer)
    }

    /** A registered client - or one whose id is the URL of its metadata document, fetched now (CIMD). */
    private Map clientFor(String clientId, String redirect) {
        if (clientId in clients) {
            return clients[clientId]
        }
        if (!cimd || !clientId?.startsWith("http")) {
            return null
        }
        documentFetches.incrementAndGet()
        if (cimdFetchFails) {
            return null
        }
        def response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(clientId)).GET().build(),
                HttpResponse.BodyHandlers.ofString())
        Map document = ObjectMapper.getDefault().readValue(response.body(), Map)
        if (response.statusCode() != 200 || document.client_id != clientId || !(redirect in document.redirect_uris)) {
            return null
        }
        [secret: null, method: "none", redirectUris: document.redirect_uris]
    }

    private void token(HttpExchange exchange, Map<String, String> params) {
        if (tokenEndpointFails) {
            json(exchange, 500, [error: "server_error"])
            return
        }
        String clientId = authenticatedClient(exchange, params)
        if (clientId == null) {
            json(exchange, 401, [error: "invalid_client"])
            return
        }
        if (params.grant_type == "authorization_code") {
            duringExchange?.call()
            Map issued = codes.remove(params.code)
            if (refuseExchange || issued == null || issued.clientId != clientId || issued.redirectUri != params.redirect_uri
                    || issued.resource != params.resource || s256(params.code_verifier) != issued.challenge) {
                json(exchange, 400, [error: "invalid_grant", error_description: "SECRET-DESCRIPTION"])
                return
            }
            json(exchange, 200, tokens(issued.scope as String))
        } else if (params.grant_type == "refresh_token") {
            refreshes.incrementAndGet()
            if (slowRefreshMillis > 0) {
                Thread.sleep(slowRefreshMillis)
            }
            if (refuseRefresh || !refreshTokens.contains(params.refresh_token) || !params.resource) {
                json(exchange, 400, [error: "invalid_grant"])
                return
            }
            if (rotate) {
                refreshTokens.remove(params.refresh_token)
            }
            Map answer = tokens(null)
            if (!rotate) {
                refreshTokens.remove(answer.refresh_token)
                answer.remove("refresh_token")
            }
            json(exchange, 200, answer)
        } else {
            json(exchange, 400, [error: "unsupported_grant_type"])
        }
    }

    /** The client the request authenticates as - by basic, by post, or a public client's id; null when it fails. */
    private String authenticatedClient(HttpExchange exchange, Map<String, String> params) {
        String authorization = exchange.requestHeaders.getFirst("Authorization")
        String id
        String secret
        String method
        if (authorization?.startsWith("Basic ")) {
            def parts = new String(authorization.substring(6).decodeBase64(), StandardCharsets.UTF_8).split(":", 2)
            id = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
            secret = URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
            method = "client_secret_basic"
        } else if (params.client_secret) {
            id = params.client_id
            secret = params.client_secret
            method = "client_secret_post"
        } else {
            id = params.client_id
            secret = null
            method = "none"
        }
        Map client = clients[id]
        if (client == null) {
            // A client known by its metadata document is public.
            return cimd && id?.startsWith("http") && method == "none" ? id : null
        }
        client.method == method && client.secret == secret ? id : null
    }

    private Map tokens(String scope) {
        String access = "access-" + UUID.randomUUID()
        String refresh = "refresh-" + UUID.randomUUID()
        issuedTokens << access
        refreshTokens << refresh
        Map answer = [access_token: access, token_type: "Bearer", expires_in: tokenLifetime, refresh_token: refresh]
        if (scope) {
            answer.scope = scope
        }
        answer
    }

    private static String s256(String verifier) {
        if (verifier == null) {
            return null
        }
        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))
                .encodeBase64Url().toString().replace("=", "")
    }

    private static Map<String, String> form(String text) {
        Map<String, String> values = [:]
        (text ?: "").split("&").findAll { it }.each { pair ->
            def parts = pair.split("=", 2)
            values[URLDecoder.decode(parts[0], StandardCharsets.UTF_8)] =
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : ""
        }
        values
    }

    private static void back(HttpExchange exchange, String redirect, Map answer) {
        String query = answer.findAll { it.value != null }
                .collect { URLEncoder.encode(it.key as String, StandardCharsets.UTF_8) + "=" +
                        URLEncoder.encode(it.value as String, StandardCharsets.UTF_8) }.join("&")
        exchange.responseHeaders.add("Location", redirect + (redirect.contains("?") ? "&" : "?") + query)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private static void json(HttpExchange exchange, int status, Map body) {
        byte[] bytes = ObjectMapper.getDefault().writeValueAsString(body).getBytes(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length)
        exchange.responseBody.write(bytes)
        exchange.close()
    }
}
