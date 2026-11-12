package de.alpdesk.controller.common

import de.alpdesk.security.ActiveTenantUserRule
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * Behind a reverse proxy under a sub-path (micronaut.server.context-path): every route lives under it, Swagger finds
 * itself there, and the app's shell gets it as its base href.
 */
@MicronautTest(transactional = false)
@Property(name = "micronaut.server.context-path", value = "/alpdesk-ai")
class ContextPathSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    EmbeddedServer server

    def "the routes live under the context path - and only there"() {
        expect:
        status("/alpdesk-ai/watchdog") == HttpStatus.OK
        status("/alpdesk-ai/branding") == HttpStatus.OK
    }

    def "a path outside the context path is refused like any route that does not exist"() {
        // reject-not-found answers 401 to an anonymous request that reaches no route; since Micronaut 5.2.6 the
        // security filter sees the paths outside the context path too, so they are refused the same way
        expect:
        status("/watchdog") == HttpStatus.UNAUTHORIZED
        status("/alpdesk-ai/no-such-route") == HttpStatus.UNAUTHORIZED
    }

    def "the A2A card and the extensions' descriptions live under the context path, and the card names it"() {
        when:
        def card = client.toBlocking().retrieve(HttpRequest.GET("/alpdesk-ai/.well-known/agent-card.json"), Map)

        then:
        (card.supportedInterfaces as List<Map>)[0].url.endsWith("/alpdesk-ai/a2a")
        ((card.capabilities as Map).extensions as List<Map>)*.params*.documentation.every { it.contains("/alpdesk-ai/a2a/extensions/") }
        client.toBlocking().retrieve(HttpRequest.GET("/alpdesk-ai/a2a/extensions/asker/1")).contains("urn:alpdesk-ai:a2a:asker:1")
    }

    def "an MCP key opens /a2a under the context path only with its switch"() {
        given:
        def tenant = client.toBlocking().retrieve(HttpRequest.POST("/alpdesk-ai/admin/tenants", [name: "Firma " + UUID.randomUUID()])
                .bearerAuth(login("alpdesk", "alpdesk")), Map)
        def key = client.toBlocking().retrieve(HttpRequest.POST("/alpdesk-ai/admin/tenants/${tenant.id}/mcp-keys",
                [name: "Nur MCP"]).bearerAuth(login("alpdesk", "alpdesk")), Map).plainKey as String
        def body = '{"jsonrpc":"2.0","id":1,"method":"GetExtendedAgentCard","params":{}}'

        when:
        client.toBlocking().exchange(HttpRequest.POST("/alpdesk-ai/a2a", body).contentType("application/json").bearerAuth(key))

        then:
        def e = thrown(io.micronaut.http.client.exceptions.HttpClientResponseException)
        e.status == io.micronaut.http.HttpStatus.UNAUTHORIZED

        when: "a key with the switch"
        def a2aKey = client.toBlocking().retrieve(HttpRequest.POST("/alpdesk-ai/admin/tenants/${tenant.id}/mcp-keys",
                [name: "A2A", a2a: true]).bearerAuth(login("alpdesk", "alpdesk")), Map).plainKey as String
        def card = client.toBlocking().retrieve(HttpRequest.POST("/alpdesk-ai/a2a", body).contentType("application/json")
                .bearerAuth(a2aKey), Map)

        then:
        (card.result as Map).name != null
    }

    def "a login under the context path works"() {
        when:
        def login = client.toBlocking().retrieve(HttpRequest.POST("/alpdesk-ai/login",
                [username: "alpdesk", password: "alpdesk"]), Map)

        then:
        login.access_token
    }

    def "a tenant admin whose rights were taken away is refused the tenant admins' API under the context path too"() {
        given:
        def admin = login("alpdesk", "alpdesk")
        def tenant = request(admin, "POST", "/admin/tenants", [name: "Kontextpfad ${UUID.randomUUID()}".toString()])
        def username = "kontext.${UUID.randomUUID().toString().take(8)}".toString()
        def user = request(admin, "POST", "/admin/tenants/${tenant.id}/users",
                [username: username, displayName: "Maria", password: "ein-langes-passwort", admin: true])
        def token = login(username, "ein-langes-passwort")

        expect:
        status(token, "/tenant/admin/users") == HttpStatus.OK

        when: "the admin takes the rights away - the token still carries the role"
        request(admin, "PUT", "/admin/tenants/${tenant.id}/users/${user.id}", [username: username, displayName: "Maria", admin: false])

        then:
        status(token, "/tenant/admin/users") == HttpStatus.FORBIDDEN
        status(token, "/tenant/account") == HttpStatus.OK

        and: "a path written another way reaches no tenant admins' route either (sent as it is, not normalised)"
        ["//tenant/admin/users", "/tenant//admin/users", "/tenant/./admin/users", "/tenant/admin/../admin/users",
         "/TENANT/admin/users", "/tenant/ADMIN/users"].every { rawStatus(token, "/alpdesk-ai" + it) != 200 }

        cleanup:
        if (tenant) {
            client.toBlocking().exchange(HttpRequest.DELETE("/alpdesk-ai/admin/tenants/${tenant.id}").bearerAuth(admin))
        }
    }

    def "the tenant admins' API below the context path '#contextPath' is #path"() {
        expect:
        ActiveTenantUserRule.tenantAdminPath(contextPath) == path

        where:
        contextPath    || path
        null           || "/tenant/admin/"
        ""             || "/tenant/admin/"
        "/alpdesk-ai"  || "/alpdesk-ai/tenant/admin/"
        "/alpdesk-ai/" || "/alpdesk-ai/tenant/admin/"
        "alpdesk-ai"   || "/alpdesk-ai/tenant/admin/"
    }

    def "/swagger leads to Swagger UI under the context path, which it is told"() {
        when: "asked without following the redirect"
        def response = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
                .send(java.net.http.HttpRequest.newBuilder(server.URI.resolve("/alpdesk-ai/swagger")).build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding())

        then:
        response.statusCode() == 303
        // Swagger UI reads the parameter with decodeURIComponent.
        URLDecoder.decode(response.headers().firstValue("Location").get(), "UTF-8") ==
                "/alpdesk-ai/swagger-ui/index.html?contextPath=/alpdesk-ai"
    }

    def "the app's shell gets the context path as its base href: '#contextPath'"() {
        expect:
        SpaFallbackController.withBase('<head><base href="/"><title>A</title></head>', contextPath) ==
                "<head><base href=\"${base}\"><title>A</title></head>"

        where:
        contextPath    || base
        null           || "/"
        ""             || "/"
        "/alpdesk-ai"  || "/alpdesk-ai/"
        "/alpdesk-ai/" || "/alpdesk-ai/"
        "alpdesk-ai"   || "/alpdesk-ai/"
        "/tools/ai"    || "/tools/ai/"
    }

    def "the base href is found however the build writes the tag: #tag"() {
        expect:
        SpaFallbackController.withBase("<head>${tag}</head>", "/alpdesk-ai") == '<head><base href="/alpdesk-ai/"></head>'

        where:
        tag << ['<base href="/">', '<base href="/"/>', '<base href="/" />', '<base  href="/" >']
    }

    private String login(String username, String password) {
        client.toBlocking().retrieve(HttpRequest.POST("/alpdesk-ai/login", [username: username, password: password]), Map)
                .access_token
    }

    private Map request(String token, String method, String path, Map body) {
        client.toBlocking().retrieve(HttpRequest.create(HttpMethod.valueOf(method), "/alpdesk-ai" + path)
                .bearerAuth(token).body(body), Map)
    }

    private HttpStatus status(String token, String path) {
        try {
            client.toBlocking().exchange(HttpRequest.GET("/alpdesk-ai" + path).bearerAuth(token)).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    /** The path sent exactly as written - Micronaut's client could tidy it up before it leaves. */
    private int rawStatus(String token, String path) {
        java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(new URI(server.URI.toString() + path))
                .header("Authorization", "Bearer " + token).build(), java.net.http.HttpResponse.BodyHandlers.discarding())
                .statusCode()
    }

    private HttpStatus status(String path) {
        try {
            client.toBlocking().exchange(HttpRequest.GET(path)).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }
}
