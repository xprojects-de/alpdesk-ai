package de.alpdesk.controller.mcp

import de.alpdesk.repositories.monitoring.DailyReportRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.mcp.FakeMcpSoftware
import de.alpdesk.services.oauth.FakeAuthorizationServer
import de.alpdesk.services.oauth.FakeReachability
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.annotation.Client
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Connected software whose server signs in with OAuth (docs/architecture/mcp.md "Signing in to the software's
 * server"): its 401 names the authorization server - the connection is kept with "sign-in available", a tenant admin
 * signs in once in the browser, the tools are read with the token, every call sends it and renews it once after a 401.
 * The same end-to-end sign-in for every shape a real server takes; the servers are fakes in the JVM.
 */
@MicronautTest(transactional = false)
class McpConnectionSignInSpec extends Specification {

    static final String QUESTION = 'Ist Rechnung 4711 bezahlt? [[call Buchhaltung__find_invoice {"number":"4711"}]]'

    @Inject
    @Client("/")
    io.micronaut.http.client.HttpClient client

    @Inject
    EmbeddedServer server

    @Inject
    FakeAiModels fake

    @Inject
    FakeReachability reachability

    @Inject
    DailyReportRepository dailyReport

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
        fake.reset()
        reachability.reset()
        software.oauth = authServer
    }

    def cleanup() {
        authServer.stop()
        software.stop()
    }

    def "every shape of server: #shape - signs in, reads the tools, a call uses the token, renews it once; no token goes out"() {
        given:
        configure(authServer, software, reachability)
        def setup = tenantWithChat()

        when: "the connection is made: the server asks for a sign-in"
        def created = connect(setup.admin)

        then:
        created.toolsError == null
        (created.tools as List).isEmpty()
        created.signIn.status == "AVAILABLE"
        created.signIn.scopesOffered == (scope ? scope.split(" ") as List : [])

        when: "the tenant admin signs in"
        if (byHand) {
            assert api.status(setup.admin, "POST", "/tenant/admin/mcp-connections/${created.id}/sign-in",
                    [returnUrl: returnUrl()]) == HttpStatus.CONFLICT
            def needed = connection(setup.admin, created.id)
            assert needed.signIn.clientIdNeeded
            authServer.portalClient("portal-client", "portal-secret", needed.signIn.redirectUri as String)
            api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${created.id}",
                    [name: "Buchhaltung", baseUrl: software.url(), clientId: "portal-client", clientSecret: "portal-secret"])
        }
        String back = signIn(setup.admin, created.id)
        def signedIn = connection(setup.admin, created.id)

        then:
        back == returnUrl() + "?connection=${created.id}&signIn=ok"
        signedIn.signIn.status == "SIGNED_IN"
        (signedIn.tools as List<Map>)*.name.sort() == ["cancel_invoice", "find_invoice"]
        authServer.requests("/authorize")[0].params.scope == scope
        authServer.requests("/token")*.params*.resource.every { it == software.url() }
        software.received.findAll { it.method == "tools/list" }.last().authorization.substring(7) in authServer.issuedTokens

        when: "a user's question calls a tool - the token goes along"
        enableFindInvoice(setup, signedIn)
        def answer = api.ask(setup.token, [modelId: setup.chat.id, message: QUESTION]).answer as Map

        then:
        answer.content.contains("Invoice 4711: 412,50 EUR, paid")
        authServer.valid(software.calls().last().authorization)

        when: "the server no longer takes the token: renewed once, the call repeated"
        authServer.revoke(software.calls().last().authorization.substring(7))
        def again = api.ask(setup.token, [modelId: setup.chat.id, message: QUESTION]).answer as Map

        then:
        again.content.contains("Invoice 4711: 412,50 EUR, paid")
        authServer.refreshes.get() == 1
        connection(setup.admin, created.id).signIn.status == "SIGNED_IN"

        and: "no token, secret or verifier in any answer"
        String listed = ObjectMapper.getDefault().writeValueAsString(api.call(setup.admin, "GET", "/tenant/admin/mcp-connections"))
        !authServer.issuedTokens.any { listed.contains(it) }
        !authServer.refreshTokens.any { listed.contains(it) }
        !listed.contains("portal-secret") && !listed.contains("secret-")

        where:
        shape                                | scope        | byHand | configure
        "the shape probed on 8 Nov 2026"     | "read write" | false  | { FakeAuthorizationServer s, FakeMcpSoftware m, FakeReachability r -> s.issuerPath = "/mcp"; s.issParameter = true }
        "an identity provider apart"         | "read write" | false  | { FakeAuthorizationServer s, FakeMcpSoftware m, FakeReachability r -> s.metadataAt = "openid"; s.authMethods = ["client_secret_basic"]; s.rotate = false; m.headerScope = null }
        "issuer with a path, OpenID below it" | null        | false  | { FakeAuthorizationServer s, FakeMcpSoftware m, FakeReachability r -> s.issuerPath = "/tenant1"; s.metadataAt = "openid-below"; s.authMethods = ["client_secret_post"]; s.issParameter = true; m.headerScope = null; m.metadataScopes = [] }
        "a public installation, CIMD"        | "read write" | false  | { FakeAuthorizationServer s, FakeMcpSoftware m, FakeReachability r -> s.cimd = true; s.issParameter = true; r.publicAddress = true }
        "the company network, CIMD offered"  | "read write" | false  | { FakeAuthorizationServer s, FakeMcpSoftware m, FakeReachability r -> s.cimd = true; s.issParameter = true }
        "nothing but a portal"               | "read write" | true   | { FakeAuthorizationServer s, FakeMcpSoftware m, FakeReachability r -> s.withRegistration = false; s.authMethods = ["client_secret_basic"]; s.issParameter = true }
        "resource metadata at the root only" | "read write" | false  | { FakeAuthorizationServer s, FakeMcpSoftware m, FakeReachability r -> m.headerNamesMetadata = false; m.metadataAtRoot = true; s.issParameter = true }
    }

    def "the shapes that end where they should: the CIMD row registers nothing, the company network never shows its document"() {
        given:
        authServer.cimd = true
        reachability.publicAddress = publicAddress
        def setup = tenantWithChat()
        def created = connect(setup.admin)

        when:
        signIn(setup.admin, created.id)

        then:
        authServer.requests("/register").size() == registrations
        authServer.documentFetches.get() == fetches

        where:
        publicAddress | registrations | fetches
        true          | 0             | 1
        false         | 1             | 0
    }

    def "a server that cannot be used for a sign-in says why in toolsError: #reason"() {
        given:
        change(authServer, software)
        def setup = tenantWithChat()

        when:
        def created = connect(setup.admin)

        then:
        created.toolsError.contains(message)
        created.signIn == null

        where:
        reason                            | message                                        | change
        "a 401 without metadata (a key)"  | "refused our key"                              | { FakeAuthorizationServer s, FakeMcpSoftware m -> m.headerWithoutMetadata = true }
        "no authorization code"           | "no sign-in in the browser"                    | { FakeAuthorizationServer s, FakeMcpSoftware m -> s.grantTypes = ["client_credentials"] }
        "no PKCE with S256"               | "PKCE"                                         | { FakeAuthorizationServer s, FakeMcpSoftware m -> s.challengeMethods = ["plain"] }
        "an issuer the document does not name" | "description could not be read"           | { FakeAuthorizationServer s, FakeMcpSoftware m -> s.announcedIssuer = "http://127.0.0.1:1/other" }
    }

    def "resource metadata named on another host is ignored - the well-known address of the software is asked"() {
        given:
        software.metadataUrlOverride = "http://127.0.0.1:1/.well-known/oauth-protected-resource"
        def setup = tenantWithChat()

        when:
        def created = connect(setup.admin)

        then:
        created.signIn.status == "AVAILABLE"
        software.metadataRequests == ["/.well-known/oauth-protected-resource/mcp"]
    }

    def "a refused renewal asks for a new sign-in: the tool call says so, the daily report names it, signing in again repairs it"() {
        given:
        def setup = tenantWithChat()
        def created = connect(setup.admin)
        signIn(setup.admin, created.id)
        enableFindInvoice(setup, connection(setup.admin, created.id))
        api.ask(setup.token, [modelId: setup.chat.id, message: QUESTION])
        authServer.refuseRefresh = true
        authServer.revoke(software.calls().last().authorization.substring(7))

        when:
        def answer = api.ask(setup.token, [modelId: setup.chat.id, message: QUESTION]).answer as Map

        then:
        answer.content.contains("needs a sign-in")
        connection(setup.admin, created.id).signIn.status == "SIGN_IN_NEEDED"
        dailyReport.signInsNeeded().any { it.connection() == "Buchhaltung" && it.tenant() == setup.tenant.name }

        when:
        authServer.refuseRefresh = false
        signIn(setup.admin, created.id)
        def repaired = api.ask(setup.token, [modelId: setup.chat.id, message: QUESTION]).answer as Map

        then:
        connection(setup.admin, created.id).signIn.status == "SIGNED_IN"
        repaired.content.contains("Invoice 4711: 412,50 EUR, paid")
    }

    def "a token refused right after its renewal: the sign-in is lost - the chip and the daily report say so"() {
        given:
        def setup = tenantWithChat()
        def created = connect(setup.admin)
        signIn(setup.admin, created.id)
        enableFindInvoice(setup, connection(setup.admin, created.id))
        software.refuseEveryToken = true

        when:
        def answer = api.ask(setup.token, [modelId: setup.chat.id, message: QUESTION]).answer as Map

        then:
        answer.content.contains("needs a sign-in")
        authServer.refreshes.get() == 1
        connection(setup.admin, created.id).signIn.status == "SIGN_IN_NEEDED"
        connection(setup.admin, created.id).signIn.lastError == "the server refused the renewed token too - sign in again"
        dailyReport.signInsNeeded().any { it.tenant() == setup.tenant.name }
    }

    def "a server asking for more rights (403 insufficient_scope) fails the call readably - no renewal"() {
        given:
        def setup = tenantWithChat()
        def created = connect(setup.admin)
        signIn(setup.admin, created.id)
        enableFindInvoice(setup, connection(setup.admin, created.id))
        software.insufficientScope = true

        when:
        def answer = api.ask(setup.token, [modelId: setup.chat.id, message: QUESTION]).answer as Map

        then:
        answer.content.contains("needs a sign-in")
        authServer.refreshes.get() == 0
    }

    def "a refusal at the server's consent page comes back as denied - the connection stays available"() {
        given:
        authServer.deny = true
        def setup = tenantWithChat()
        def created = connect(setup.admin)

        when:
        String back = signIn(setup.admin, created.id)

        then:
        back.endsWith("signIn=denied")
        connection(setup.admin, created.id).signIn.status == "AVAILABLE"
        connection(setup.admin, created.id).signIn.lastError == "the sign-in was refused at the server"
    }

    def "the sign-in goes: removed by hand, by a key entered, by another address, with the connection"() {
        given:
        def setup = tenantWithChat()
        def created = connect(setup.admin)
        signIn(setup.admin, created.id)
        String path = "/tenant/admin/mcp-connections/${created.id}"

        when: "removed"
        api.status(setup.admin, "DELETE", "$path/sign-in")

        then:
        connection(setup.admin, created.id).signIn == null
        grants(created.id) == 0

        when: "a key replaces a new sign-in"
        api.call(setup.admin, "POST", "$path/reload")
        signIn(setup.admin, created.id)
        api.call(setup.admin, "PUT", path, [name: "Buchhaltung", baseUrl: software.url(), apiKey: "software-key-123"])

        then:
        grants(created.id) == 0

        when: "another address drops it"
        api.call(setup.admin, "PUT", path, [name: "Buchhaltung", baseUrl: software.url(), removeApiKey: true])
        api.call(setup.admin, "POST", "$path/reload")
        signIn(setup.admin, created.id)
        def other = new FakeMcpSoftware()
        api.call(setup.admin, "PUT", path, [name: "Buchhaltung", baseUrl: other.url()])

        then:
        grants(created.id) == 0

        when: "deleted with the connection"
        api.call(setup.admin, "PUT", path, [name: "Buchhaltung", baseUrl: software.url()])
        signIn(setup.admin, created.id)
        api.status(setup.admin, "DELETE", path)

        then:
        grants(created.id) == 0

        cleanup:
        other?.stop()
    }

    def "signing in: a page to return to elsewhere is 400, a server that asked for none 409, another tenant's connection 404"() {
        given:
        def setup = tenantWithChat()
        def created = connect(setup.admin)
        def plain = new FakeMcpSoftware()
        def withoutOAuth = api.call(setup.admin, "POST", "/tenant/admin/mcp-connections", [name: "Lager", baseUrl: plain.url()]) as Map
        def otherTenant = api.tenant()
        def otherAdmin = api.tenantAdminToken(otherTenant.id)

        expect:
        api.status(setup.admin, "POST", "/tenant/admin/mcp-connections/${created.id}/sign-in",
                [returnUrl: "https://evil.example.com/app"]) == HttpStatus.BAD_REQUEST
        api.status(setup.admin, "POST", "/tenant/admin/mcp-connections/${withoutOAuth.id}/sign-in",
                [returnUrl: returnUrl()]) == HttpStatus.CONFLICT
        api.status(otherAdmin, "POST", "/tenant/admin/mcp-connections/${created.id}/sign-in",
                [returnUrl: returnUrl()]) == HttpStatus.NOT_FOUND
        api.status(otherAdmin, "DELETE", "/tenant/admin/mcp-connections/${created.id}/sign-in") == HttpStatus.NOT_FOUND
        api.status(null, "POST", "/admin/tenants/${otherTenant.id}/mcp-connections/${created.id}/sign-in",
                [returnUrl: returnUrl()]) == HttpStatus.UNAUTHORIZED
        api.status(api.adminToken, "POST", "/admin/tenants/${otherTenant.id}/mcp-connections/${created.id}/sign-in",
                [returnUrl: returnUrl()]) == HttpStatus.NOT_FOUND
        grants(created.id) == 1

        cleanup:
        plain.stop()
    }

    def "a sign-in link passed on signs in no one: only who started it completes it - another tenant's admin 404, another admin of the tenant 409"() {
        given:
        def setup = tenantWithChat()
        def created = connect(setup.admin)
        def started = api.call(setup.admin, "POST", "/tenant/admin/mcp-connections/${created.id}/sign-in",
                [returnUrl: returnUrl()]) as Map
        String page = follow(started.authorizationUrl as String)
        def otherAdmin = api.tenantAdminToken(api.tenant().id)
        def colleague = api.tenantAdminToken(setup.tenant.id)

        expect: "the callback sent the browser on without signing in"
        page.contains("&signIn=complete&")
        connection(setup.admin, created.id).signIn.status == "PENDING"
        authServer.requests("/token").isEmpty()

        and: "another tenant's admin: no such connection; a colleague and the admin's mirror: no such sign-in"
        api.status(otherAdmin, "POST", "/tenant/admin/mcp-connections/${created.id}/sign-in/complete",
                [callback: page.substring(page.indexOf("&signIn=complete&") + 17)]) == HttpStatus.NOT_FOUND
        api.completeSignIn(colleague, "/tenant/admin/mcp-connections", page).endsWith("signIn=stateUnknown")
        api.completeSignIn(api.adminToken, "/admin/tenants/${setup.tenant.id}/mcp-connections", page)
                .endsWith("signIn=stateUnknown")
        authServer.requests("/token").isEmpty()

        and: "the one who started it"
        api.completeSignIn(setup.admin, "/tenant/admin/mcp-connections", page).endsWith("signIn=ok")
        connection(setup.admin, created.id).signIn.status == "SIGNED_IN"
    }

    def "the admin's mirror signs a tenant's connection in the same way"() {
        given:
        def setup = tenantWithChat()
        def created = connect(setup.admin)
        String path = "/admin/tenants/${setup.tenant.id}/mcp-connections/${created.id}"

        when:
        def started = api.admin("POST", "$path/sign-in", [returnUrl: returnUrl()]) as Map
        String back = api.completeSignIn(api.adminToken, "/admin/tenants/${setup.tenant.id}/mcp-connections",
                follow(started.authorizationUrl as String))

        then:
        back.endsWith("signIn=ok")
        (api.admin("GET", "/admin/tenants/${setup.tenant.id}/mcp-connections") as List<Map>)[0].signIn.status == "SIGNED_IN"

        when:
        def removed = api.status(api.adminToken, "DELETE", "$path/sign-in")

        then:
        removed == HttpStatus.NO_CONTENT
        grants(created.id) == 0
    }

    // --- helpers ---

    String returnUrl() {
        "http://127.0.0.1:4300/app/software"
    }

    Map tenantWithChat() {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = api.chatModel()
        api.allow(tenant.id, chat)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        def user = api.user(tenant.id)
        [tenant: tenant, admin: admin, chat: chat, user: user, token: user.token as String]
    }

    Map connect(String admin) {
        api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Buchhaltung", baseUrl: software.url()]) as Map
    }

    Map connection(String admin, Object id) {
        (api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>).find { it.id == id }
    }

    void enableFindInvoice(Map setup, Map connection) {
        def find = (connection.tools as List<Map>).find { it.name == "find_invoice" }
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${find.id}", [enabled: true])
        api.grant(setup.admin, connection.id, setup.user.id, "READ")
    }

    /**
     * Signs in as the tenant admin's browser: our endpoint, the server's page, our callback, the page completing it
     * with the admin's session - the outcome as the page shows it.
     */
    String signIn(String admin, Object connectionId) {
        def started = api.call(admin, "POST", "/tenant/admin/mcp-connections/${connectionId}/sign-in",
                [returnUrl: returnUrl()]) as Map
        api.completeSignIn(admin, "/tenant/admin/mcp-connections", follow(started.authorizationUrl as String))
    }

    /** The server's page and our callback: the page the callback sends the browser on to. */
    String follow(String authorizationUrl) {
        def atServer = get(authorizationUrl)
        assert atServer.statusCode() == 302
        def callback = get(atServer.headers().firstValue("Location").get())
        assert callback.statusCode() == 302
        callback.headers().firstValue("Location").get()
    }

    HttpResponse<String> get(String url) {
        browser.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    int grants(Object connectionId) {
        managementDatabase.connection.withCloseable { db ->
            def result = db.prepareStatement("SELECT COUNT(*) FROM oauth_grant WHERE connection_id = ${connectionId}").executeQuery()
            result.next()
            result.getInt(1)
        }
    }
}
