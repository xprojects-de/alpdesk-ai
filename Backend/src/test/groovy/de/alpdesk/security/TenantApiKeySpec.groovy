package de.alpdesk.security

import io.micronaut.context.ApplicationContext
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * The separation between the kinds of callers is the security boundary of the whole app: an admin JWT must never
 * reach the tenant API, a tenant API key or a tenant user's JWT must never reach the admin API, and a key stops
 * working the moment it is revoked, its tenant is deactivated or deleted. (Tenant users: TenantUserSpec.)
 */
@MicronautTest
class TenantApiKeySpec extends Specification {

    /** What a frontend of the tenant's own may use - published in Swagger next to /api/v1 (decided 6 November 2026). */
    static final List<String> PUBLISHED = ["/api/v1", "/login", "/branding", "/tenant/account", "/tenant/branding",
                                           "/tenant/chat"]
    static final List<String> USER_CONTROLLERS = ["WamLoginController", "AccountController", "ChatController",
                                                  "SoftwareChoiceController", "SoftwarePromptController",
                                                  "BrandingController", "TenantBrandingController"]

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    ApplicationContext applicationContext

    @Shared
    String adminToken

    def setup() {
        if (!adminToken) {
            adminToken = client.toBlocking().retrieve(
                    HttpRequest.POST("/login", [username: "alpdesk", password: "alpdesk"]), Map).access_token
        }
    }

    def "a tenant's API key reaches the tenant API and nothing of the admin API"() {
        given:
        def tenant = createTenant()
        def key = createKey(tenant.id)

        expect: "the key identifies its tenant"
        get("/api/v1/me", key).name == tenant.name
        get("/api/v1/me", key).id == tenant.id

        and: "admin endpoints are forbidden for it"
        status("/admin/tenants", key) == HttpStatus.FORBIDDEN
        status("/version", key) == HttpStatus.FORBIDDEN

        and: "the admin JWT in turn is not a tenant"
        status("/api/v1/me", adminToken) == HttpStatus.FORBIDDEN
        request(adminToken, "GET", "/api/v1/documents") == HttpStatus.FORBIDDEN
        request(adminToken, "POST", "/api/v1/chat/stream") == HttpStatus.FORBIDDEN
        request(adminToken, "GET", "/api/v1/tasks") == HttpStatus.FORBIDDEN
        request(adminToken, "GET", "/api/v1/software") == HttpStatus.FORBIDDEN
    }

    def "a tenant's key is refused on every admin route - also with its own tenant id in the path"() {
        given:
        def tenant = createTenant()
        def key = createKey(tenant.id)
        def t = "/admin/tenants/${tenant.id}"

        expect:
        request(key, method, path.replace("T", t)) == HttpStatus.FORBIDDEN

        where:
        method   | path
        "GET"    | "/admin/tenants"
        "POST"   | "/admin/tenants"
        "GET"    | "T"
        "PUT"    | "T"
        "DELETE" | "T"
        "GET"    | "T/api-keys"
        "POST"   | "T/api-keys"
        "PUT"    | "T/api-keys/1"
        "DELETE" | "T/api-keys/1"
        "GET"    | "T/users"
        "POST"   | "T/users"
        "PUT"    | "T/users/1"
        "DELETE" | "T/users/1"
        "POST"   | "T/users/from-tenant"
        "GET"    | "T/groups"
        "POST"   | "T/groups"
        "PUT"    | "T/groups/1"
        "DELETE" | "T/groups/1"
        "GET"    | "T/models"
        "PUT"    | "T/models"
        "GET"    | "/tenant/account"
        "PUT"    | "/tenant/account/password"
        "PUT"    | "/tenant/account/language"
        "POST"   | "/tenant/account/switch"
        "GET"    | "/admin/models"
        "POST"   | "/admin/models"
        "GET"    | "/admin/provider-keys"
        "PUT"    | "/admin/provider-keys/OPENAI"
        "GET"    | "/admin/usage"
        "GET"    | "/admin/access-requests"
        "POST"   | "/admin/access-requests/1/assign"
        "DELETE" | "/admin/access-requests/1"
        "POST"   | "/admin/daily-report/send"
        "GET"    | "/admin/application-log"
        "GET"    | "/tenant/admin/provider-keys"
        "GET"    | "/tenant/ai/models"
        "GET"    | "/tenant/admin/users"
        "GET"    | "/tenant/admin/groups"
        "GET"    | "/tenant/admin/api-keys"
        "GET"    | "/tenant/admin/usage"
        "GET"    | "T/provider-keys"
        "PUT"    | "T/provider-keys/OPENAI"
        "GET"    | "T/documents"
        "POST"   | "T/documents"
        "POST"   | "T/documents/archive"
        "GET"    | "T/assistant"
        "GET"    | "T/assistant/image-models"
        "POST"   | "T/assistant/templates"
        "PUT"    | "T/embedding-model"
        "PUT"    | "T/reranking-model"
        "PUT"    | "T/search-language"
        "POST"   | "T/documents/embed-again"
        "PUT"    | "T/documents/1/area"
        "DELETE" | "T/documents/uploads?area=company"
        "GET"    | "T/knowledge-areas"
        "POST"   | "T/knowledge-areas"
        "PUT"    | "T/knowledge-areas/1"
        "DELETE" | "T/knowledge-areas/1"
        "GET"    | "T/doc-sites"
        "POST"   | "T/doc-sites"
        "DELETE" | "T/doc-sites/1"
        "POST"   | "T/doc-sites/1/address"
        "GET"    | "T/branding"
        "POST"   | "T/branding/login-key"
        "GET"    | "/tenant/admin/branding/login-key"
        "GET"    | "/tenant/admin/documents"
        "POST"   | "/tenant/admin/documents/archive"
        "PUT"    | "/tenant/admin/documents/1/area"
        "DELETE" | "/tenant/admin/documents/uploads?area=company"
        "GET"    | "/tenant/admin/knowledge-areas"
        "POST"   | "/tenant/admin/knowledge-areas"
        "PUT"    | "/tenant/admin/knowledge-areas/1"
        "DELETE" | "/tenant/admin/knowledge-areas/1"
        "GET"    | "/tenant/admin/doc-sites"
        "POST"   | "/tenant/admin/doc-sites"
        "GET"    | "/tenant/doc-sites"
        "POST"   | "/tenant/doc-sites/1/address"
        "GET"    | "/tenant/admin/assistant"
        "GET"    | "/tenant/admin/assistant/image-models"
        "GET"    | "T/mcp-keys"
        "POST"   | "T/mcp-keys"
        "PUT"    | "T/mcp-keys/1"
        "POST"   | "T/mcp-keys/1/renew"
        "DELETE" | "T/mcp-keys/1"
        "GET"    | "T/mcp-connections"
        "POST"   | "T/mcp-connections"
        "PUT"    | "T/mcp-connections/1/pass-through"
        "PUT"    | "T/mcp-connections/1/resources"
        "PUT"    | "T/mcp-connections/1/prompts/1"
        "GET"    | "T/file-stores"
        "POST"   | "T/file-stores"
        "POST"   | "T/file-stores/1/check"
        "PUT"    | "T/file-stores/1/fingerprint"
        "GET"    | "T/file-stores/1/folders"
        "GET"    | "T/knowledge-sources"
        "POST"   | "T/knowledge-sources"
        "PUT"    | "T/knowledge-sources/1"
        "DELETE" | "T/knowledge-sources/1"
        "POST"   | "T/knowledge-sources/1/run"
        "POST"   | "T/knowledge-sources/1/cancel"
        "GET"    | "T/knowledge-sources/1/files"
        "GET"    | "T/tasks"
        "POST"   | "T/tasks"
        "POST"   | "T/tasks/validate"
        "POST"   | "T/tasks/1/runs"
        "DELETE" | "T/tasks/1/runs/1"
        "POST"   | "T/tasks/1/runs/1/cancel"
        "POST"   | "T/tasks/draft"
        "GET"    | "T/tasks/draft-models"
        "GET"    | "T/tasks/reference"
        "GET"    | "/tenant/admin/mcp-keys"
        "PUT"    | "/tenant/admin/mcp-keys/1"
        "POST"   | "/tenant/admin/mcp-keys/1/renew"
        "GET"    | "/tenant/admin/mcp-connections"
        "PUT"    | "/tenant/admin/mcp-connections/1/prompts/1"
        "GET"    | "/tenant/admin/file-stores"
        "POST"   | "/tenant/admin/file-stores/1/check"
        "GET"    | "/tenant/admin/file-stores/1/folders"
        "GET"    | "/tenant/admin/knowledge-sources"
        "POST"   | "/tenant/admin/knowledge-sources/1/run"
        "GET"    | "/tenant/admin/knowledge-sources/1/files"
        "GET"    | "/tenant/admin/tasks"
        "POST"   | "/tenant/admin/tasks/1/runs"
        "POST"   | "/tenant/admin/tasks/1/runs/1/cancel"
        "POST"   | "/tenant/admin/tasks/draft"
        "POST"   | "/mcp"
        "GET"    | "/tenant/chat/templates"
        "POST"   | "/tenant/chat/stream"
        "POST"   | "/tenant/chat/actions/1/run"
        "POST"   | "/tenant/chat/actions/1/cancel"
        "GET"    | "/tenant/chat/conversations"
        "GET"    | "/tenant/chat/prompts"
        "POST"   | "/tenant/chat/prompts/1"
        "GET"    | "/tenant/chat/software"
    }

    def "without credentials only login, the liveness probe and the frontend are open"() {
        expect:
        request(null, "GET", "/api/v1/me") == HttpStatus.UNAUTHORIZED
        request(null, "GET", "/api/v1/documents") == HttpStatus.UNAUTHORIZED
        request(null, "GET", "/api/v1/tasks") == HttpStatus.UNAUTHORIZED
        request(null, "GET", "/api/v1/software") == HttpStatus.UNAUTHORIZED
        request(null, "GET", "/admin/tenants") == HttpStatus.UNAUTHORIZED
        request(null, "GET", "/version") == HttpStatus.UNAUTHORIZED
        request(null, "GET", "/tenant/account") == HttpStatus.UNAUTHORIZED
        request(null, "GET", "/watchdog") == HttpStatus.OK
        request(null, "GET", "/login/wam") == HttpStatus.OK
        request(null, "GET", "/admin/access-requests") == HttpStatus.UNAUTHORIZED

        and: "a wrong password gets no token"
        request(null, "POST", "/login", [username: "alpdesk", password: "falsch"]) == HttpStatus.UNAUTHORIZED
        request(null, "POST", "/login", [username: "admin", password: "alpdesk"]) == HttpStatus.UNAUTHORIZED
        request("ai_unknownkey", "GET", "/api/v1/me") == HttpStatus.UNAUTHORIZED
    }

    /** @Hidden is checked through the generated documentation below (the annotation is compile-time only). */
    def "every controller is secured with exactly one role that matches its path"() {
        given:
        def anonymous = ["GlobalExceptionHandler", "SpaFallbackController", "HealthController", "SwaggerController",
                         "BrandingController", "WamLoginController", "DocSiteFilesController",
                         "A2aExtensionController", "OAuthCallbackController"] as Set
        def controllers = applicationContext.getBeanDefinitions(Qualifiers
                .byStereotype(Controller))
                .findAll { it.beanType.name.startsWith("de.alpdesk.controller.") }

        expect:
        controllers.size() >= 19
        controllers.each { definition ->
            String name = definition.beanType.simpleName
            List<String> roles = definition.stringValues(Secured) as List
            String path = definition.stringValue(Controller).orElse("")
            if (name in anonymous) {
                assert roles == [SecurityRule.IS_ANONYMOUS], name
            } else {
                assert roles.size() == 1 && roles[0] in [Roles.ADMIN, Roles.TENANT, Roles.TENANT_USER, Roles.TENANT_ADMIN, Roles.MCP], "$name: $roles"
                assert (roles[0] == Roles.MCP) == (path in ["/mcp", "/a2a"]), "$name: $path must match its role"
                assert (roles[0] == Roles.TENANT) == (path ==~ $/\/api\/v1(\/.*)?/$), "$name: $path must match its role"
                assert (roles[0] == Roles.TENANT_ADMIN) == (path ==~ $/\/tenant\/admin(\/.*)?/$), "$name: $path must match its role"
                assert (roles[0] == Roles.TENANT_USER) == (path ==~ $/\/tenant(\/.*)?/$ && !(path ==~ $/\/tenant\/admin(\/.*)?/$)), "$name: $path must match its role"
            }
        }
    }

    def "a sign-in's way back and our client document are anonymous - a callback without a valid state is 400, never 401"() {
        expect:
        request(null, "GET", "/oauth/connections/callback") == HttpStatus.BAD_REQUEST
        request(null, "GET", "/oauth/connections/callback?state=unknown&code=x") == HttpStatus.BAD_REQUEST
        request(null, "GET", "/oauth/client-metadata.json") == HttpStatus.OK
    }

    def "the MCP server - Micronaut MCP's controller, without @Secured - is reached with the MCP key only"() {
        given:
        def rules = applicationContext.getProperty("micronaut.security.intercept-url-map", List).orElse([]) as List<Map>

        expect:
        [rules.find { it.pattern == "/mcp" }?.access].flatten() == [Roles.MCP]
        [rules.find { it.pattern == "/a2a" }?.access].flatten() == [Roles.MCP]
        applicationContext.getProperty("micronaut.mcp.server.endpoint", String).orElse(null) == "/mcp"
    }

    def "the published contract is made of DTOs only - no entity (storage format) in any request or response"() {
        given: "every type reachable from the published controllers' parameters and return values - /api/v1 and the users'"
        def controllers = applicationContext.getBeanDefinitions(Qualifiers
                .byStereotype(Controller))
                .findAll { it.beanType.name.startsWith("de.alpdesk.controller.api.v1.") || it.beanType.simpleName in USER_CONTROLLERS }*.beanType
        Set<Class> seen = [] as Set
        Closure visit
        visit = { Type type ->
            if (type instanceof ParameterizedType) {
                type.actualTypeArguments.each { visit(it) }
                visit(type.rawType)
            } else if (type instanceof WildcardType) {
                type.upperBounds.each { visit(it) }
            } else if (type instanceof Class && type.name.startsWith("de.alpdesk.") && seen.add(type)) {
                if (type.isRecord()) {
                    type.recordComponents.each { visit(it.genericType) }
                }
                if (type.isSealed()) {
                    type.permittedSubclasses.each { visit(it) }
                }
            }
        }
        controllers.each { controller ->
            controller.declaredMethods.findAll { Modifier.isPublic(it.modifiers) }.each { method ->
                visit(method.genericReturnType)
                method.genericParameterTypes.each { visit(it) }
            }
        }

        expect:
        controllers.size() >= 2 + USER_CONTROLLERS.size()
        seen.any { it.simpleName == "DocumentResponse" }
        seen.any { it.simpleName == "AccountResponse" } && seen.any { it.simpleName == "SoftwareChoiceResponse" }
        seen.any { it.simpleName == "SkillChoiceResponse" }
        seen.any { it.simpleName == "MessageResponse" } && seen.any { it.simpleName == "ApiModelResponse" }
        seen.findAll { it.name.startsWith("de.alpdesk.entities.") }.isEmpty()
    }

    def "Micronaut's management endpoints are switched off - for anonymous callers, tenants and the admin alike"() {
        given:
        def key = createKey(createTenant().id)

        expect:
        ["/health", "/beans", "/loggers", "/env", "/flyway", "/routes", "/info", "/refresh"].every { path ->
            [null, key, adminToken].every { token ->
                def status = request(token, "GET", path)
                assert status in [HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN], "$path ($token): $status"
                true
            }
        }
        request(null, "GET", "/watchdog") == HttpStatus.OK
    }

    def "the published API documentation holds /api/v1 and the users' sign-in, account and chat - nothing of the admins"() {
        given:
        def yaml = getClass().classLoader.getResources("META-INF/swagger").toList()
                .collectMany { url -> new File(url.toURI()).listFiles().findAll { it.name.endsWith(".yml") } }
                .collect { it.text }

        expect:
        yaml.size() == 1
        // Named as people read it: the product, the major version of the release.
        yaml[0] =~ /(?m)^  title: Zirmo API$/
        yaml[0] =~ /(?m)^  version: "?2\.0"?$/
        def paths = (yaml[0] =~ /(?m)^  (\/[^:]*):$/).collect { it[1] }
        paths.any { it.startsWith("/api/v1/") }
        paths.containsAll(["/login", "/login/wam", "/tenant/account", "/tenant/account/switch", "/tenant/chat/stream",
                           "/tenant/chat/software", "/branding/login/{loginKey}"])
        paths.every { path -> PUBLISHED.any { path == it || path.startsWith(it + "/") } }
        !paths.any { it.startsWith("/admin") || it.startsWith("/tenant/admin") || it.startsWith("/tenant/ai") || it == "/mcp"
                || it.startsWith("/oauth") }
    }

    def "the full key is only returned once and never stored in plain text"() {
        given:
        def tenant = createTenant()
        def created = client.toBlocking().retrieve(
                HttpRequest.POST("/admin/tenants/${tenant.id}/api-keys", [name: "Produktion"]).bearerAuth(adminToken), Map)

        expect:
        created.plainKey.startsWith("ai_")
        created.plainKey.startsWith(created.key.keyPrefix)

        when:
        def listed = client.toBlocking().retrieve(
                HttpRequest.GET("/admin/tenants/${tenant.id}/api-keys").bearerAuth(adminToken), Argument.listOf(Map))

        then:
        listed.size() == 1
        !listed[0].containsKey("plainKey")
        !listed.toString().contains(created.plainKey)
    }

    def "the admin creates a key without history and switches it - never through another tenant's path"() {
        given:
        def tenant = createTenant()
        def other = createTenant()
        def created = client.toBlocking().retrieve(HttpRequest.POST("/admin/tenants/${tenant.id}/api-keys",
                [name: "Leitstand", keepsHistory: false]).bearerAuth(adminToken), Map)

        expect:
        created.key.keepsHistory == false
        request(adminToken, "PUT", "/admin/tenants/${other.id}/api-keys/${created.key.id}", [keepsHistory: true]) == HttpStatus.NOT_FOUND
        request(adminToken, "PUT", "/admin/tenants/${tenant.id}/api-keys/${created.key.id}", [name: ""]) == HttpStatus.BAD_REQUEST

        when:
        def updated = client.toBlocking().retrieve(HttpRequest.PUT("/admin/tenants/${tenant.id}/api-keys/${created.key.id}",
                [keepsHistory: true]).bearerAuth(adminToken), Map)

        then:
        updated.keepsHistory == true
        updated.name == "Leitstand"
    }

    def "a revoked key, an inactive tenant and a deleted tenant all end access immediately"() {
        given:
        def tenant = createTenant()
        def revoked = createKey(tenant.id)
        def keyId = client.toBlocking().retrieve(
                HttpRequest.GET("/admin/tenants/${tenant.id}/api-keys").bearerAuth(adminToken), Argument.listOf(Map))[0].id

        when: "the key is revoked"
        client.toBlocking().exchange(HttpRequest.DELETE("/admin/tenants/${tenant.id}/api-keys/${keyId}").bearerAuth(adminToken))

        then:
        status("/api/v1/me", revoked) == HttpStatus.UNAUTHORIZED

        when: "the tenant is deactivated"
        def key = createKey(tenant.id)
        client.toBlocking().exchange(HttpRequest.PUT("/admin/tenants/${tenant.id}",
                [name: tenant.name, active: false]).bearerAuth(adminToken))

        then:
        status("/api/v1/me", key) == HttpStatus.UNAUTHORIZED

        when: "it is reactivated and then deleted"
        client.toBlocking().exchange(HttpRequest.PUT("/admin/tenants/${tenant.id}",
                [name: tenant.name, active: true]).bearerAuth(adminToken))
        def before = status("/api/v1/me", key)
        client.toBlocking().exchange(HttpRequest.DELETE("/admin/tenants/${tenant.id}").bearerAuth(adminToken))

        then:
        before == HttpStatus.OK
        status("/api/v1/me", key) == HttpStatus.UNAUTHORIZED
    }

    def "an unknown or malformed key is rejected"() {
        expect:
        status("/api/v1/me", "ai_doesnotexist") == HttpStatus.UNAUTHORIZED
        status("/api/v1/me", "not-a-key") == HttpStatus.UNAUTHORIZED
    }

    def "a key of one tenant cannot be revoked through another tenant's path"() {
        given:
        def owner = createTenant()
        def other = createTenant()
        def key = createKey(owner.id)
        def keyId = client.toBlocking().retrieve(
                HttpRequest.GET("/admin/tenants/${owner.id}/api-keys").bearerAuth(adminToken), Argument.listOf(Map))[0].id

        when:
        client.toBlocking().exchange(HttpRequest.DELETE("/admin/tenants/${other.id}/api-keys/${keyId}").bearerAuth(adminToken))

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
        status("/api/v1/me", key) == HttpStatus.OK
    }

    def "tenant names are unique regardless of case"() {
        given:
        def tenant = createTenant()

        when:
        client.toBlocking().exchange(HttpRequest.POST("/admin/tenants",
                [name: tenant.name.toUpperCase(), active: true]).bearerAuth(adminToken), Map)

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.CONFLICT
    }

    private Map createTenant() {
        client.toBlocking().retrieve(HttpRequest.POST("/admin/tenants",
                [name: "Firma " + UUID.randomUUID(), active: true]).bearerAuth(adminToken), Map)
    }

    private String createKey(Object tenantId) {
        client.toBlocking().retrieve(HttpRequest.POST("/admin/tenants/${tenantId}/api-keys",
                [name: "Test"]).bearerAuth(adminToken), Map).plainKey
    }

    private Map get(String path, String token) {
        client.toBlocking().retrieve(HttpRequest.GET(path).bearerAuth(token), Map)
    }

    private HttpStatus status(String path, String token) {
        request(token, "GET", path)
    }

    private HttpStatus request(String token, String method, String path, Map body = null) {
        try {
            def request = HttpRequest.create(HttpMethod.valueOf(method), path)
            if (body != null || method in ["POST", "PUT"]) {
                request = request.body(body ?: [:])
            }
            if (token) {
                request = request.bearerAuth(token)
            }
            client.toBlocking().exchange(request, byte[]).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }
}
