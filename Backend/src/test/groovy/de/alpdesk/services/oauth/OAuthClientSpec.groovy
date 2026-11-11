package de.alpdesk.services.oauth

import de.alpdesk.entities.oauth.ClientIdSource
import de.alpdesk.entities.oauth.GrantStatus
import de.alpdesk.entities.oauth.OAuthGrant
import de.alpdesk.repositories.oauth.OAuthGrantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.ModelServers
import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.mcp.FakeMcpSoftware
import de.alpdesk.services.mcp.McpAddresses
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.http.client.annotation.Client
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import javax.sql.DataSource
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * A connection's sign-in at the authorization server its software named, protocol-neutral: the server's metadata in
 * the specification's order, our client id (held, our metadata document, a registration, by hand), the authorization
 * request with PKCE and the resource, the callback once with the issuer checked, the tokens encrypted, the refresh
 * with rotation - also two at once. The authorization server is the fake in the JVM; nothing real is called.
 */
@MicronautTest(transactional = false)
class OAuthClientSpec extends Specification {

    static final String RETURN = "http://127.0.0.1:4300/app/software"

    @Inject
    @Client("/")
    io.micronaut.http.client.HttpClient client

    @Inject
    EmbeddedServer server

    @Inject
    OAuthClient oauth

    @Inject
    OAuthMetadata metadata

    @Inject
    OAuthGrantRepository grants

    @Inject
    FakeReachability reachability

    @Inject
    McpAddresses mcpAddresses

    @Inject
    ModelServers modelServers

    @Shared
    AiApi api

    FakeAuthorizationServer authServer = new FakeAuthorizationServer()
    FakeMcpSoftware software = new FakeMcpSoftware()
    HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    DataSource managementDatabase

    @Inject
    void setManagementDatabase(DataSource dataSource) {
        managementDatabase = DelegatingDataSource.unwrapDataSource(dataSource)
    }

    def setup() {
        api = api ?: new AiApi(client)
        reachability.reset()
    }

    def cleanup() {
        authServer.stop()
        software.stop()
    }

    def "the server's metadata is read where the specification says, in its order"() {
        given:
        authServer.issuerPath = issuerPath
        authServer.metadataAt = metadataAt

        when:
        def found = metadata.read(authServer.issuer(), "Buchhaltung")

        then:
        found.issuer() == authServer.issuer()
        found.tokenEndpoint() == authServer.origin() + "/token"
        authServer.received*.path == tried

        where:
        issuerPath | metadataAt     | tried
        ""         | "oauth"        | ["/.well-known/oauth-authorization-server"]
        ""         | "openid"       | ["/.well-known/oauth-authorization-server", "/.well-known/openid-configuration"]
        "/mcp"     | "oauth"        | ["/.well-known/oauth-authorization-server/mcp"]
        "/tenant1" | "openid"       | ["/.well-known/oauth-authorization-server/tenant1", "/.well-known/openid-configuration/tenant1"]
        "/tenant1" | "openid-below" | ["/.well-known/oauth-authorization-server/tenant1", "/.well-known/openid-configuration/tenant1",
                                       "/tenant1/.well-known/openid-configuration"]
    }

    def "a server that cannot be used is refused with its reason, naming the connection"() {
        given:
        change(authServer)

        when:
        metadata.read(authServer.issuer(), "Buchhaltung")

        then:
        def e = thrown(ServiceException)
        e.code() == code
        e.params() == [name: "Buchhaltung"]
        !e.message.contains("127.0.0.1")

        where:
        code                       | change
        "oauth.metadataUnreadable" | { FakeAuthorizationServer s -> s.metadataAt = "nowhere" }
        "oauth.metadataUnreadable" | { FakeAuthorizationServer s -> s.announcedIssuer = "http://127.0.0.1:1/other" }
        "oauth.noAuthorizationCode" | { FakeAuthorizationServer s -> s.grantTypes = ["client_credentials"] }
        "oauth.pkceUnsupported"    | { FakeAuthorizationServer s -> s.challengeMethods = ["plain"] }
        "oauth.metadataUnreadable" | { FakeAuthorizationServer s -> s.withoutFields = ["authorization_endpoint"] }
        "oauth.metadataUnreadable" | { FakeAuthorizationServer s -> s.withoutFields = ["token_endpoint"] }
    }

    def "the browser is sent to a web address only - a server naming anything else as its sign-in page starts no sign-in"() {
        given:
        authServer.authorizationEndpoint = signInPage
        def grant = prepared()

        when:
        oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)

        then:
        def e = thrown(ServiceException)
        e.code() == code
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.AVAILABLE
        authServer.requests("/register").isEmpty()

        where: "127.0.0.1 is a host the installation names - the rule for plain HTTP alone would let its first line pass"
        signInPage                                   | code
        "javascript://127.0.0.1/%0Aalert(1)//"       | "oauth.metadataUnreadable"
        "javascript:alert(1)"                        | "oauth.metadataUnreadable"
        "https://user@sign-in.example.com/authorize" | "oauth.metadataUnreadable"
        "http://sign-in.example.com/authorize"       | "modelServer.plainHttpRefused"
    }

    def "a sign-in page only the browser reaches is taken - we never call it, so its name is not resolved"() {
        given:
        authServer.authorizationEndpoint = "https://sign-in.invalid/authorize"
        def grant = prepared()

        expect:
        oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null).toString()
                .startsWith("https://sign-in.invalid/authorize?")
    }

    def "a server's metadata on an address the rules refuse is never fetched"() {
        when:
        metadata.read(scheme + "://localhost:" + authServer.http.address.port, "Buchhaltung")

        then:
        def e = thrown(ServiceException)
        e.code() == code
        authServer.received.isEmpty()

        where: "localhost is internal and not named by the installation, unlike the fakes' 127.0.0.1"
        scheme  | code
        "http"  | "modelServer.plainHttpRefused"
        "https" | "mcpServer.internalNetwork"
    }

    def "a registration endpoint the rules refuse is never called"() {
        given:
        authServer.registrationEndpoint = "http://localhost:${authServer.http.address.port}/register"
        def grant = prepared()

        when:
        oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)

        then:
        def e = thrown(ServiceException)
        e.code() == "modelServer.plainHttpRefused"
        authServer.requests("/register").isEmpty()
    }

    def "a token endpoint the rules refuse is never called - the sign-in fails readably"() {
        given:
        authServer.tokenEndpoint = "http://localhost:${authServer.http.address.port}/token"
        def grant = prepared()

        when:
        def back = signIn(grant)

        then:
        back.endsWith("signIn=exchangeFailed")
        authServer.requests("/token").isEmpty()
        grants.findByConnectionId(grant.connectionId()).get().accessToken() == null
    }

    @Timeout(20)
    def "an answer that stalls after its headers ends at the deadline - nothing waits on its body for good"() {
        given:
        authServer.stallMillis = 8000
        def http = new OAuthHttp(mcpAddresses, modelServers, Duration.ofSeconds(1))

        when:
        http.get(authServer.origin() + "/stall", "Buchhaltung")

        then:
        def e = thrown(ServiceException)
        e.code() == "oauth.timeout"
    }

    def "a redirect is never followed - a server cannot send a request of ours on to another host"() {
        given:
        def other = new FakeAuthorizationServer()
        authServer.redirectTo = other.origin() + "/token"
        def http = new OAuthHttp(mcpAddresses, modelServers, Duration.ofSeconds(5))

        when:
        def answer = http.get(authServer.origin() + "/redirect", "Buchhaltung")

        then:
        answer.status() == 302
        other.received.isEmpty()

        cleanup:
        other.stop()
    }

    def "an answer larger than 64 KB is not taken"() {
        given:
        def http = new OAuthHttp(mcpAddresses, modelServers, Duration.ofSeconds(5))

        when:
        http.get(authServer.origin() + "/large", "Buchhaltung")

        then:
        def e = thrown(ServiceException)
        e.code() == "oauth.unreachable"
    }

    def "a sign-in asks for the code with PKCE (S256), the resource, the offered scopes and a state kept on the grant"() {
        given:
        def grant = prepared()

        when:
        URI url = oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)
        Map<String, String> params = query(url)
        def pending = grants.findByConnectionId(grant.connectionId()).get()

        then:
        url.toString().startsWith(authServer.origin() + "/authorize?")
        params.response_type == "code"
        params.code_challenge_method == "S256"
        params.resource == software.url()
        params.scope == "read write"
        params.redirect_uri == origin() + "/oauth/connections/callback"
        params.state == pending.state()
        params.code_challenge == challengeOf(pending)
        pending.status() == GrantStatus.PENDING
        pending.returnUrl() == RETURN
        pending.pendingUntil().isAfter(Instant.now().plus(Duration.ofMinutes(9)))
        pending.codeVerifier().startsWith("enc:v1:")
    }

    def "the callback signs in once: tokens encrypted, the scopes granted, the resource sent in both requests"() {
        given:
        def grant = prepared()

        when:
        def back = signIn(grant)
        def signedIn = grants.findByConnectionId(grant.connectionId()).get()

        then:
        back == RETURN + "?connection=${grant.connectionId()}&signIn=ok"
        signedIn.status() == GrantStatus.SIGNED_IN
        signedIn.accessToken().startsWith("enc:v1:")
        signedIn.refreshToken().startsWith("enc:v1:")
        signedIn.scopesGranted() == "read write"
        signedIn.state() == null && signedIn.codeVerifier() == null && signedIn.pendingUntil() == null
        authServer.requests("/authorize")[0].params.resource == software.url()
        authServer.requests("/token")[0].params.resource == software.url()
        oauth.accessToken(grant.connectionId(), "Buchhaltung", grant.resource()) in authServer.issuedTokens

        when: "the same state comes again"
        def again = get(lastCallback)

        then:
        again.statusCode() == 400
        again.body().contains("no longer valid")
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.SIGNED_IN
    }

    def "a sign-in under way still completes when a refused refresh marked the grant meanwhile"() {
        given:
        authServer.tokenLifetime = 30
        def grant = prepared()
        signIn(grant)
        URI url = oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)
        String callback = get(url.toString()).headers().firstValue("Location").get()
        authServer.refuseRefresh = true

        when:
        oauth.accessToken(grant.connectionId(), "Buchhaltung", grant.resource())

        then:
        thrown(ServiceException)
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.SIGN_IN_NEEDED

        when:
        authServer.refuseRefresh = false
        def back = complete(grant, get(callback))

        then:
        back.endsWith("signIn=ok")
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.SIGNED_IN
    }

    def "a callback with a state but without a code is refused readably"() {
        given:
        def grant = prepared()
        URI url = oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)
        String state = query(url).state

        when:
        def back = complete(grant, get(origin() + "/oauth/connections/callback?state=" + state))

        then:
        back.endsWith("signIn=exchangeFailed")
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.AVAILABLE
    }

    def "a state nobody started finds nothing and stores nothing"() {
        given:
        def grant = prepared()
        oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)

        when:
        def answer = get(origin() + "/oauth/connections/callback?code=x&state=" + state)

        then:
        answer.statusCode() == 400
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.PENDING

        where:
        state << ["unknown-state", ""]
    }

    def "the callback only sends the browser on - the sign-in is completed by who started it, for nobody else"() {
        given: "a tenant admin (user 7) started the sign-in"
        def grant = prepared()
        URI url = oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, 7L)
        def back = get(get(url.toString()).headers().firstValue("Location").get())

        expect: "the callback exchanged nothing"
        back.statusCode() == 302
        back.headers().firstValue("Location").get().contains("&signIn=complete&")
        authServer.requests("/token").isEmpty()
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.PENDING

        when: "another user, the admin, another connection or another tenant brings the answer"
        def others = [complete(grant, back, 8L), complete(grant, back, null)]
        def otherConnection = oauth.complete(URI.create(callbackOf(back)), grant.tenantId(), grant.connectionId() + 1000, 7L)
        def otherTenant = oauth.complete(URI.create(callbackOf(back)), grant.tenantId() + 1000, grant.connectionId(), 7L)

        then: "unknown to each of them, and the sign-in still waits for its owner"
        others.every { it.endsWith("signIn=stateUnknown") }
        !otherConnection.ok() && !otherTenant.ok()
        authServer.requests("/token").isEmpty()
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.PENDING

        when:
        def own = complete(grant, back, 7L)

        then:
        own.endsWith("signIn=ok")
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.SIGNED_IN
    }

    def "an A2A card naming another token URL starts the grant over - the client's secret never goes to it"() {
        given: "a server known by its two URLs, a client id and secret entered by hand, signed in"
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Agent " + UUID.randomUUID().toString().take(6), baseUrl: software.url()]) as Map
        def server = AuthorizationServer.ofEndpoints(authServer.origin() + "/authorize", authServer.origin() + "/token")
        def grant = oauth.prepare(tenant.id as long, connection.id as long, software.url(), server, "read")
        oauth.enterClient(grant.connectionId(), "Agent", "portal-client", "portal-secret")

        when: "the card names the same sign-in page and another token URL"
        def moved = AuthorizationServer.ofEndpoints(authServer.origin() + "/authorize", "https://tokens.example.com/token")
        def after = oauth.prepare(tenant.id as long, connection.id as long, software.url(), moved, "read")

        then:
        after.clientId() == null
        after.clientSecret() == null
        after.tokenEndpoint() == "https://tokens.example.com/token"

        when: "the same token URL again keeps what was entered"
        oauth.enterClient(grant.connectionId(), "Agent", "portal-client", "portal-secret")
        def same = oauth.prepare(tenant.id as long, connection.id as long, software.url(), moved, "read")

        then:
        same.clientId() == "portal-client"
    }

    def "a server known by its two URLs alone named no issuer - the iss it sends, whatever its path, is not compared"() {
        given: "a card's flow URLs of a server whose issuer has a path and that sends iss with the callback"
        authServer.issuerPath = "/realms/muster"
        authServer.issParameter = true
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Agent " + UUID.randomUUID().toString().take(6), baseUrl: software.url()]) as Map
        def server = AuthorizationServer.ofEndpoints(authServer.origin() + "/authorize", authServer.origin() + "/token")
        def grant = oauth.prepare(tenant.id as long, connection.id as long, software.url(), server, "read")
        authServer.portalClient("portal-client", "portal-secret", origin() + "/oauth/connections/callback")
        oauth.enterClient(grant.connectionId(), "Agent", "portal-client", "portal-secret")

        when:
        def back = signIn(grant)
        def after = grants.findByConnectionId(grant.connectionId()).get()

        then:
        lastCallback.contains("iss=")
        back.endsWith("signIn=ok")
        after.issuer() == AuthorizationServer.NO_ISSUER
        after.status() == GrantStatus.SIGNED_IN

        and: "the tenant admin is shown the host of its sign-in page"
        OAuthClient.view(after).issuer() == "127.0.0.1"
    }

    def "a sign-in left too long expires and the grant goes back to where it was"() {
        given:
        def grant = prepared()
        URI url = oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)
        String callback = get(url.toString()).headers().firstValue("Location").get()
        sql("UPDATE oauth_grant SET pending_until = now() - interval '1 minute' WHERE connection_id = ${grant.connectionId()}")

        when:
        def answer = complete(grant, get(callback))
        def after = grants.findByConnectionId(grant.connectionId()).get()

        then:
        answer.endsWith("signIn=stateExpired")
        after.status() == GrantStatus.AVAILABLE
        after.state() == null
        authServer.requests("/token").isEmpty()
    }

    def "an answer from another issuer is refused - its error texts are neither shown nor stored"() {
        given:
        authServer.issParameter = true
        change(authServer)
        def grant = prepared()

        when:
        def back = signIn(grant)
        def after = grants.findByConnectionId(grant.connectionId()).get()

        then:
        back.endsWith("signIn=issuerMismatch")
        after.status() == GrantStatus.AVAILABLE
        after.accessToken() == null
        authServer.requests("/token").isEmpty()

        where:
        change << [{ FakeAuthorizationServer s -> s.wrongIss = true }, { FakeAuthorizationServer s -> s.issMissing = true }]
    }

    def "a refusal at the consent page says so and keeps a signed-in grant signed in"() {
        given:
        def grant = prepared()
        signIn(grant)
        authServer.deny = true

        when:
        def back = signIn(grant)
        def after = grants.findByConnectionId(grant.connectionId()).get()

        then:
        back.endsWith("signIn=denied")
        after.status() == GrantStatus.SIGNED_IN
        after.lastError() == "the sign-in was refused at the server"
        !after.toString().contains("SECRET-DESCRIPTION")
    }

    def "a refused exchange is reported and stores no token"() {
        given:
        authServer.refuseExchange = true
        def grant = prepared()

        when:
        def back = signIn(grant)
        def after = grants.findByConnectionId(grant.connectionId()).get()

        then:
        back.endsWith("signIn=exchangeFailed")
        after.status() == GrantStatus.AVAILABLE
        after.accessToken() == null
        after.lastError().contains("invalid_grant")
        !after.lastError().contains("SECRET")
    }

    def "our client id: the metadata document when the server reads one and our address is public - no registration"() {
        given:
        authServer.cimd = true
        reachability.publicAddress = true
        def grant = prepared()

        when:
        def back = signIn(grant)
        def after = grants.findByConnectionId(grant.connectionId()).get()

        then:
        back.endsWith("signIn=ok")
        after.clientId() == origin() + "/oauth/client-metadata.json"
        after.clientIdSource() == ClientIdSource.METADATA_DOCUMENT
        after.clientSecret() == null
        authServer.documentFetches.get() == 1
        authServer.requests("/register").isEmpty()
    }

    def "a server that cannot read our document is remembered - the next sign-in registers"() {
        given:
        authServer.cimd = true
        authServer.cimdFetchFails = true
        reachability.publicAddress = true
        def grant = prepared()

        when:
        def first = signIn(grant)

        then:
        first.endsWith("signIn=denied")
        with(grants.findByConnectionId(grant.connectionId()).get()) {
            !metadataDocumentSupported()
            clientId() == null
            lastError() == "the server could not read the installation's client document"
        }

        when:
        def second = signIn(grant)

        then:
        second.endsWith("signIn=ok")
        grants.findByConnectionId(grant.connectionId()).get().clientIdSource() == ClientIdSource.REGISTERED
    }

    def "a sign-in through our document left pending past its time is taken for unreadable - the next one registers"() {
        given:
        authServer.cimd = true
        reachability.publicAddress = true
        def grant = prepared()
        oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)
        sql("UPDATE oauth_grant SET pending_until = now() - interval '1 minute' WHERE connection_id = ${grant.connectionId()}")

        when:
        oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)

        then:
        grants.findByConnectionId(grant.connectionId()).get().clientIdSource() == ClientIdSource.REGISTERED
        authServer.requests("/register").size() == 1
    }

    def "a signed-in grant keeps its client id when a sign-in over it fails or is left - its tokens stay usable"() {
        given:
        authServer.cimd = true
        authServer.tokenLifetime = 30
        reachability.publicAddress = true
        def grant = prepared()
        assert signIn(grant).endsWith("signIn=ok")
        String clientId = grants.findByConnectionId(grant.connectionId()).get().clientId()

        when: "the server cannot read our document this time"
        authServer.cimdFetchFails = true
        def refused = signIn(grant)
        def after = grants.findByConnectionId(grant.connectionId()).get()

        then:
        refused.endsWith("signIn=denied")
        after.status() == GrantStatus.SIGNED_IN
        after.clientId() == clientId
        after.clientIdSource() == ClientIdSource.METADATA_DOCUMENT

        when: "a sign-in over it is left past its time - the next one keeps the document too"
        authServer.cimdFetchFails = false
        oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)
        sql("UPDATE oauth_grant SET pending_until = now() - interval '1 minute' WHERE connection_id = ${grant.connectionId()}")
        oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)

        then:
        grants.findByConnectionId(grant.connectionId()).get().clientId() == clientId
        authServer.requests("/register").isEmpty()

        and: "the token is renewed with that client id"
        oauth.accessToken(grant.connectionId(), "Buchhaltung", grant.resource()) in authServer.issuedTokens
    }

    def "a grant whose client id is gone asks for a new sign-in instead of failing the call"() {
        given:
        authServer.tokenLifetime = 30
        def grant = prepared()
        signIn(grant)
        sql("UPDATE oauth_grant SET client_id = NULL WHERE connection_id = ${grant.connectionId()}")

        when:
        oauth.accessToken(grant.connectionId(), "Buchhaltung", grant.resource())

        then:
        def e = thrown(ServiceException)
        e.code() == "oauth.signInNeeded"
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.SIGN_IN_NEEDED
    }

    def "tokens no longer readable (APP_SECRET_KEY changed) ask for a new sign-in instead of failing the call"() {
        given:
        def grant = prepared()
        signIn(grant)
        sql("UPDATE oauth_grant SET access_token = 'enc:v1:AAAA' WHERE connection_id = ${grant.connectionId()}")

        when:
        oauth.accessToken(grant.connectionId(), "Buchhaltung", grant.resource())

        then:
        def e = thrown(ServiceException)
        e.code() == "oauth.signInNeeded"
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.SIGN_IN_NEEDED
    }

    def "a sign-in nobody finished is shown as over once its time passed - no endless 'sign-in running'"() {
        given:
        def grant = prepared()
        oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)

        expect:
        OAuthClient.view(grants.findByConnectionId(grant.connectionId()).get()).status() == "PENDING"

        when:
        sql("UPDATE oauth_grant SET pending_until = now() - interval '1 minute' WHERE connection_id = ${grant.connectionId()}")

        then:
        OAuthClient.view(grants.findByConnectionId(grant.connectionId()).get()).status() == "AVAILABLE"
    }

    def "a server reading documents is registered at when our address is private - it never fetches the document"() {
        given:
        authServer.cimd = true
        def grant = prepared()

        when:
        def back = signIn(grant)

        then:
        back.endsWith("signIn=ok")
        grants.findByConnectionId(grant.connectionId()).get().clientIdSource() == ClientIdSource.REGISTERED
        authServer.documentFetches.get() == 0
    }

    def "a registration asks as a web application for the code and the refresh token - a secret only when needed"() {
        given:
        authServer.authMethods = methods
        def grant = prepared()

        when:
        def back = signIn(grant)
        def after = grants.findByConnectionId(grant.connectionId()).get()
        Map request = authServer.clients[after.clientId()].request as Map

        then:
        back.endsWith("signIn=ok")
        request.application_type == "web"
        request.grant_types as Set == ["authorization_code", "refresh_token"] as Set
        request.redirect_uris == [origin() + "/oauth/connections/callback"]
        request.client_name == "Zirmo"
        request.token_endpoint_auth_method == method
        (after.clientSecret() != null) == withSecret
        after.clientSecret() == null || after.clientSecret().startsWith("enc:v1:")

        where:
        methods                                  | method                | withSecret
        ["none", "client_secret_basic"]          | "none"                | false
        ["client_secret_basic"]                  | "client_secret_basic" | true
        ["client_secret_post"]                   | "client_secret_post"  | true
    }

    def "a refused registration and a server without any way to a client id are reported"() {
        given:
        change(authServer)
        def grant = prepared()

        when:
        oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)

        then:
        def e = thrown(ServiceException)
        e.code() == code
        def view = OAuthClient.view(grants.findByConnectionId(grant.connectionId()).get())
        view.clientIdNeeded() == byHand
        !byHand || view.redirectUri() == origin() + "/oauth/connections/callback"

        where:
        code                        | byHand | change
        "oauth.registrationRefused" | false  | { FakeAuthorizationServer s -> s.refuseRegistration = true }
        "oauth.registrationNeeded"  | true   | { FakeAuthorizationServer s -> s.withRegistration = false }
    }

    def "a client id entered by hand signs in with its secret"() {
        given:
        authServer.withRegistration = false
        def grant = prepared()
        authServer.portalClient("portal-client", "portal-secret", origin() + "/oauth/connections/callback")

        when:
        oauth.enterClient(grant.connectionId(), "Buchhaltung", "portal-client", "portal-secret")
        def back = signIn(grant)

        then:
        back.endsWith("signIn=ok")
        authServer.requests("/token")[0].authorization.startsWith("Basic ")
        OAuthClient.view(grants.findByConnectionId(grant.connectionId()).get()).clientIdNeeded()
    }

    def "a client id belongs to its issuer - another issuer starts over"() {
        given:
        def grant = prepared()
        signIn(grant)
        def other = new FakeAuthorizationServer()

        when:
        oauth.prepare(grant.tenantId(), grant.connectionId(), software.url(), metadata.read(other.issuer(), "Buchhaltung"), null)
        def after = grants.findByConnectionId(grant.connectionId()).get()

        then:
        after.issuer() == other.issuer()
        after.clientId() == null
        after.accessToken() == null
        after.status() == GrantStatus.AVAILABLE

        cleanup:
        other.stop()
    }

    def "the access token is renewed when it expires within a minute - a rotated refresh token is kept"() {
        given:
        authServer.tokenLifetime = lifetime
        authServer.rotate = rotate
        def grant = prepared()
        signIn(grant)
        def before = grants.findByConnectionId(grant.connectionId()).get()
        // Signed in, the software's tools were read already - with a token renewed there when it expires soon.
        int earlier = authServer.refreshes.get()

        when:
        String token = oauth.accessToken(grant.connectionId(), "Buchhaltung", grant.resource())
        def after = grants.findByConnectionId(grant.connectionId()).get()

        then:
        authServer.refreshes.get() - earlier == refreshes
        token in authServer.issuedTokens
        (after.refreshToken() != before.refreshToken()) == (refreshes == 1 && rotate)
        authServer.requests("/token").every { it.params.resource == software.url() }

        where:
        lifetime | rotate | refreshes
        3600     | true   | 0
        30       | true   | 1
        30       | false  | 1
    }

    def "a refused refresh asks for a new sign-in; a server that fails leaves the grant as it is"() {
        given:
        authServer.tokenLifetime = 30
        def grant = prepared()
        signIn(grant)
        change(authServer)

        when:
        oauth.accessToken(grant.connectionId(), "Buchhaltung", grant.resource())

        then:
        def e = thrown(ServiceException)
        e.code() == code
        e.params() == [name: "Buchhaltung"]
        grants.findByConnectionId(grant.connectionId()).get().status() == status

        where:
        code                  | status                     | change
        "oauth.signInNeeded"  | GrantStatus.SIGN_IN_NEEDED | { FakeAuthorizationServer s -> s.refuseRefresh = true }
        "oauth.refreshFailed" | GrantStatus.SIGNED_IN      | { FakeAuthorizationServer s -> s.tokenEndpointFails = true }
    }

    def "two calls renewing at once send one refresh - both get the one new token"() {
        given:
        authServer.tokenLifetime = 30
        authServer.slowRefreshMillis = 1500
        def grant = prepared()
        signIn(grant)
        int earlier = authServer.refreshes.get()
        def pool = Executors.newFixedThreadPool(2)

        when:
        def tokens = pool.invokeAll([1, 2].collect { { -> oauth.accessToken(grant.connectionId(), "Buchhaltung", grant.resource()) } as Callable<String> })*.get()
        def after = grants.findByConnectionId(grant.connectionId()).get()

        then:
        authServer.refreshes.get() - earlier == 1
        tokens[0] == tokens[1]
        authServer.refreshTokens.size() == 1

        cleanup:
        pool.shutdown()
    }

    def "a token is given to the address it was signed in for only - a call still open to another address gets none"() {
        given:
        def grant = prepared()
        signIn(grant)

        when:
        oauth.accessToken(grant.connectionId(), "Buchhaltung", "https://old.example.com/mcp")

        then:
        def e = thrown(ServiceException)
        e.code() == "oauth.signInNeeded"

        when: "the old address refuses its token and asks for a renewed one"
        oauth.refresh(grant.connectionId(), "Buchhaltung", "https://old.example.com/mcp", "the-old-token")

        then:
        def refused = thrown(ServiceException)
        refused.code() == "oauth.signInNeeded"
        authServer.refreshes.get() == 0
        grants.findByConnectionId(grant.connectionId()).get().status() == GrantStatus.SIGNED_IN
    }

    def "a grant replaced while its code is exchanged gets no tokens - they were another server's"() {
        given:
        def grant = prepared()
        def other = new FakeAuthorizationServer()
        authServer.duringExchange = {
            oauth.prepare(grant.tenantId(), grant.connectionId(), "https://new.example.com/mcp",
                    metadata.read(other.issuer(), "Buchhaltung"), null)
        }

        when:
        signIn(grant)
        def after = grants.findByConnectionId(grant.connectionId()).get()

        then:
        after.id() != grant.id()
        after.issuer() == other.issuer()
        after.accessToken() == null && after.refreshToken() == null
        after.status() == GrantStatus.AVAILABLE

        cleanup:
        other.stop()
    }

    def "forgetting removes the grant"() {
        given:
        def grant = prepared()
        signIn(grant)

        when:
        oauth.forget(grant.connectionId())

        then:
        grants.findByConnectionId(grant.connectionId()).isEmpty()
    }

    def "our client metadata document: anonymous, its own address as client id, the callback as the one way back"() {
        when:
        def answer = get(origin() + "/oauth/client-metadata.json")
        Map document = io.micronaut.serde.ObjectMapper.getDefault().readValue(answer.body(), Map)

        then:
        answer.statusCode() == 200
        answer.headers().firstValue("Cache-Control").get().contains("max-age=3600")
        document.client_id == origin() + "/oauth/client-metadata.json"
        document.redirect_uris == [origin() + "/oauth/connections/callback"]
        document.token_endpoint_auth_method == "none"
        document.client_name == "Zirmo"
    }

    def "behind a context path the addresses carry it; a page to return to must be a page of the frontend on the request's host"() {
        given:
        def addresses = new OAuthAddresses("/alpdesk-ai/")

        expect:
        addresses.callback("https://ai.example.com") == "https://ai.example.com/alpdesk-ai/oauth/connections/callback"
        addresses.metadataDocument("https://ai.example.com") == "https://ai.example.com/alpdesk-ai/oauth/client-metadata.json"
        addresses.returnUrl("http://ai.example.com:4300/alpdesk-ai/app/software", "https://ai.example.com") ==
                "http://ai.example.com:4300/alpdesk-ai/app/software"
        addresses.returnUrl("https://ai.example.com/alpdesk-ai/dashboard/tenants/12/software", "https://ai.example.com") ==
                "https://ai.example.com/alpdesk-ai/dashboard/tenants/12/software"

        when:
        addresses.returnUrl(foreign, "https://ai.example.com")

        then:
        def e = thrown(ServiceException)
        e.code() == "oauth.returnUrlForeign"

        where:
        foreign << ["https://evil.example.com/alpdesk-ai/app", "https://user@ai.example.com/alpdesk-ai/app",
                    "javascript:alert(1)", "/alpdesk-ai/app/software", null,
                    // The callback sends the code there: a page of the frontend only - never a handbook's website,
                    // whose scripts could pass it on, by any spelling a browser resolves to it.
                    "https://ai.example.com/alpdesk-ai/doc-sites/token/index.html",
                    "https://ai.example.com/alpdesk-ai/app/../doc-sites/token/index.html",
                    "https://ai.example.com/alpdesk-ai/app/%2e%2e/doc-sites/token/index.html",
                    "https://ai.example.com/alpdesk-ai/app/software?next=x",
                    "https://ai.example.com/alpdesk-ai/app/software#x",
                    "https://ai.example.com/app/software"]
    }

    // --- helpers ---

    String lastCallback

    String origin() {
        "http://127.0.0.1:${server.port}"
    }

    /** A connection of a new tenant to the fake software, its grant prepared at the fake server. */
    OAuthGrant prepared() {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Buchhaltung " + UUID.randomUUID().toString().take(6), baseUrl: software.url()]) as Map
        oauth.prepare(tenant.id as long, connection.id as long, software.url(),
                metadata.read(authServer.issuer(), "Buchhaltung"), authServer.scopesSupported.join(" "))
    }

    /**
     * Through the sign-in as the admin's browser: to the server's page, back to our callback, on to the page, which
     * completes it as the admin (who started it) - the outcome as the page shows it, "<page>?connection=<id>&signIn=<ok
     * or the error's code>".
     */
    String signIn(OAuthGrant grant) {
        URI url = oauth.startSignIn(grant.connectionId(), "Buchhaltung", origin(), RETURN, null)
        def atServer = get(url.toString())
        assert atServer.statusCode() == 302
        lastCallback = atServer.headers().firstValue("Location").get()
        complete(grant, get(lastCallback))
    }

    /** The page the callback sent the browser to completes the sign-in - for userId (null: the admin). */
    /** Our callback's own address with the answer the callback passed on to the page. */
    String callbackOf(HttpResponse<String> back) {
        String page = back.headers().firstValue("Location").get()
        origin() + "/oauth/connections/callback?" + page.substring(page.indexOf("&signIn=complete&") + "&signIn=complete&".length())
    }

    String complete(OAuthGrant grant, HttpResponse<String> back, Long userId = null) {
        assert back.statusCode() == 302
        String page = back.headers().firstValue("Location").get()
        String prefix = RETURN + "?connection=${grant.connectionId()}&signIn=complete&"
        assert page.startsWith(prefix)
        def done = oauth.complete(URI.create(origin() + "/oauth/connections/callback?" + page.substring(prefix.length())),
                grant.tenantId(), grant.connectionId(), userId)
        RETURN + "?connection=${grant.connectionId()}&signIn=" + (done.ok() ? "ok" : done.errorCode().substring("oauth.".length()))
    }

    HttpResponse<String> get(String url) {
        browser.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    void sql(String statement) {
        managementDatabase.connection.withCloseable { it.prepareStatement(statement).executeUpdate() }
    }

    static Map<String, String> query(URI url) {
        url.rawQuery.split("&").collectEntries { pair ->
            def parts = pair.split("=", 2)
            [URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8)]
        }
    }

    @Inject
    de.alpdesk.security.SecretCipher cipher

    String challengeOf(OAuthGrant pending) {
        s256(cipher.decrypt(pending.codeVerifier()))
    }

    static String s256(String verifier) {
        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))
                .encodeBase64Url().toString().replace("=", "")
    }
}
