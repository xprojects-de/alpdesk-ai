package de.alpdesk.security

import com.nimbusds.jwt.JWTParser
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * Who does what in a tenant: a tenant admin keeps its tenant's settings (/tenant/admin/**: users, API keys of the
 * providers and the client software, embedding model, company knowledge, usage) and chats; a user only chats and finds
 * the company's knowledge without seeing it. A tenant admin never reaches another tenant, cannot lock itself out, and
 * loses the admin API the moment its rights are taken away.
 */
@MicronautTest(transactional = false)
class TenantAdminSpec extends Specification {

    static final String OPENING_HOURS = "Die Werkstatt ist täglich von 7 bis 17 Uhr geöffnet."

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "a tenant admin logs in with both roles and its account says so"() {
        given:
        def tenant = api.tenant()
        def tenantAdmin = api.user(tenant.id, true)

        when:
        def login = client.toBlocking().retrieve(HttpRequest.POST("/login",
                [username: tenantAdmin.username, password: AiApi.USER_PASSWORD]), Map)

        then:
        (login.roles as List).toSet() == [Roles.TENANT_USER, Roles.TENANT_ADMIN].toSet()
        JWTParser.parse(login.access_token).JWTClaimsSet.getClaim("tenantId") == tenant.id
        api.call(tenantAdmin.token, "GET", "/tenant/account").admin == true
        api.call(api.userToken(tenant.id), "GET", "/tenant/account").admin == false
    }

    def "a user reaches none of the tenant admins' API: #method #path"() {
        given:
        def token = api.userToken(api.tenant().id)

        expect:
        api.status(token, method, path) == HttpStatus.FORBIDDEN

        where:
        method   | path
        "GET"    | "/tenant/admin/users"
        "POST"   | "/tenant/admin/users"
        "PUT"    | "/tenant/admin/users/1"
        "DELETE" | "/tenant/admin/users/1"
        "GET"    | "/tenant/admin/groups"
        "POST"   | "/tenant/admin/groups"
        "PUT"    | "/tenant/admin/groups/1"
        "DELETE" | "/tenant/admin/groups/1"
        "GET"    | "/tenant/admin/api-keys"
        "POST"   | "/tenant/admin/api-keys"
        "PUT"    | "/tenant/admin/api-keys/1"
        "DELETE" | "/tenant/admin/api-keys/1"
        "GET"    | "/tenant/admin/provider-keys"
        "PUT"    | "/tenant/admin/provider-keys/OPENAI"
        "DELETE" | "/tenant/admin/provider-keys/OPENAI"
        "GET"    | "/tenant/admin/documents"
        "POST"   | "/tenant/admin/documents/archive"
        "POST"   | "/tenant/admin/documents/1/retry"
        "DELETE" | "/tenant/admin/documents/1"
        "PUT"    | "/tenant/admin/documents/1/area"
        "DELETE" | "/tenant/admin/documents/uploads?area=company"
        "GET"    | "/tenant/admin/knowledge-areas"
        "POST"   | "/tenant/admin/knowledge-areas"
        "PUT"    | "/tenant/admin/knowledge-areas/1"
        "DELETE" | "/tenant/admin/knowledge-areas/1"
        "GET"    | "/tenant/admin/doc-sites"
        "POST"   | "/tenant/admin/doc-sites"
        "DELETE" | "/tenant/admin/doc-sites/1"
        "POST"   | "/tenant/admin/doc-sites/1/address"
        "PUT"    | "/tenant/admin/embedding-model"
        "PUT"    | "/tenant/admin/reranking-model"
        "PUT"    | "/tenant/admin/search-language"
        "POST"   | "/tenant/admin/documents/embed-again"
        "GET"    | "/tenant/admin/usage"
        "GET"    | "/tenant/admin/branding/login-key"
        "GET"    | "/tenant/admin/assistant"
        "PUT"    | "/tenant/admin/assistant"
        "GET"    | "/tenant/admin/assistant/image-models"
        "POST"   | "/tenant/admin/assistant/templates"
        "PUT"    | "/tenant/admin/assistant/templates/1"
        "DELETE" | "/tenant/admin/assistant/templates/1"
        "GET"    | "/tenant/admin/mcp-keys"
        "POST"   | "/tenant/admin/mcp-keys"
        "PUT"    | "/tenant/admin/mcp-keys/1"
        "POST"   | "/tenant/admin/mcp-keys/1/renew"
        "DELETE" | "/tenant/admin/mcp-keys/1"
        "GET"    | "/tenant/admin/mcp-connections"
        "POST"   | "/tenant/admin/mcp-connections"
        "PUT"    | "/tenant/admin/mcp-connections/1"
        "DELETE" | "/tenant/admin/mcp-connections/1"
        "POST"   | "/tenant/admin/mcp-connections/1/reload"
        "POST"   | "/tenant/admin/mcp-connections/1/sign-in"
        "POST"   | "/tenant/admin/mcp-connections/1/sign-in/complete"
        "DELETE" | "/tenant/admin/mcp-connections/1/sign-in"
        "PUT"    | "/tenant/admin/mcp-connections/1/tools/1"
        "GET"    | "/tenant/admin/mcp-connections/1/access"
        "PUT"    | "/tenant/admin/mcp-connections/1/pass-through"
        "PUT"    | "/tenant/admin/mcp-connections/1/resources"
        "GET"    | "/tenant/admin/file-stores"
        "POST"   | "/tenant/admin/file-stores"
        "PUT"    | "/tenant/admin/file-stores/1"
        "DELETE" | "/tenant/admin/file-stores/1"
        "POST"   | "/tenant/admin/file-stores/1/check"
        "PUT"    | "/tenant/admin/file-stores/1/fingerprint"
        "GET"    | "/tenant/admin/file-stores/1/folders"
        "GET"    | "/tenant/admin/knowledge-sources"
        "POST"   | "/tenant/admin/knowledge-sources"
        "PUT"    | "/tenant/admin/knowledge-sources/1"
        "DELETE" | "/tenant/admin/knowledge-sources/1"
        "POST"   | "/tenant/admin/knowledge-sources/1/run"
        "POST"   | "/tenant/admin/knowledge-sources/1/cancel"
        "GET"    | "/tenant/admin/knowledge-sources/1/files"
        "GET"    | "/tenant/admin/tasks"
        "POST"   | "/tenant/admin/tasks"
        "POST"   | "/tenant/admin/tasks/validate"
        "GET"    | "/tenant/admin/tasks/1"
        "PUT"    | "/tenant/admin/tasks/1"
        "DELETE" | "/tenant/admin/tasks/1"
        "POST"   | "/tenant/admin/tasks/1/runs"
        "GET"    | "/tenant/admin/tasks/1/runs"
        "GET"    | "/tenant/admin/tasks/1/runs/1"
        "DELETE" | "/tenant/admin/tasks/1/runs"
        "POST"   | "/tenant/admin/tasks/1/runs/1/cancel"
        "POST"   | "/tenant/admin/tasks/draft"
        "GET"    | "/tenant/admin/tasks/draft-models"
        "GET"    | "/tenant/admin/tasks/reference"
    }

    def "a tenant admin manages the users of its own tenant, other admins included"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)

        when:
        def created = api.call(token, "POST", "/tenant/admin/users",
                [username: "kollege-" + AiApi.unique(), displayName: "Kollege", password: AiApi.USER_PASSWORD, admin: true])
        def updated = api.call(token, "PUT", "/tenant/admin/users/${created.id}",
                [username: created.username, displayName: "Kollegin", admin: false])

        then:
        created.admin == true
        updated.displayName == "Kollegin"
        updated.admin == false
        (api.call(token, "GET", "/tenant/admin/users") as List).collect { it.id }.contains(created.id)
        (api.admin("GET", "/admin/tenants/${tenant.id}/users") as List).find { it.id == created.id }.displayName == "Kollegin"

        when:
        def status = api.status(token, "DELETE", "/tenant/admin/users/${created.id}")

        then:
        status == HttpStatus.NO_CONTENT
        !(api.call(token, "GET", "/tenant/admin/users") as List).any { it.id == created.id }
    }

    def "a tenant admin sets a user's language like the admin - English when left out, 400 for no language tag"() {
        given:
        def token = api.tenantAdminToken(api.tenant().id)

        when:
        def created = api.call(token, "POST", "/tenant/admin/users",
                [username: "kollege-" + AiApi.unique(), displayName: "Kollege", password: AiApi.USER_PASSWORD])
        def german = api.call(token, "PUT", "/tenant/admin/users/${created.id}",
                [username: created.username, displayName: "Kollege", language: "de"])

        then:
        created.language == "en"
        german.language == "de"
        api.status(token, "PUT", "/tenant/admin/users/${created.id}",
                [username: created.username, displayName: "Kollege", language: "deutsch"]) == HttpStatus.BAD_REQUEST
        (api.call(token, "GET", "/tenant/admin/users") as List).find { it.id == created.id }.language == "de"
    }

    def "a tenant admin never reaches another tenant's users"() {
        given:
        def other = api.user(api.tenant().id)
        def token = api.tenantAdminToken(api.tenant().id)

        expect:
        !(api.call(token, "GET", "/tenant/admin/users") as List).any { it.id == other.id }
        api.status(token, "PUT", "/tenant/admin/users/${other.id}", [username: other.username, displayName: "X"]) == HttpStatus.NOT_FOUND
        api.status(token, "DELETE", "/tenant/admin/users/${other.id}") == HttpStatus.NOT_FOUND
        api.status(other.token, "GET", "/tenant/account") == HttpStatus.OK
    }

    def "a tenant admin cannot add another tenant's user - only the admin can, it would show the other tenant's users"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)
        def other = api.user(api.tenant().id)

        expect:
        api.status(token, "POST", "/admin/tenants/${tenant.id}/users/from-tenant", [userId: other.id]) == HttpStatus.FORBIDDEN
        api.status(token, "POST", "/tenant/admin/users/from-tenant", [userId: other.id]) != HttpStatus.CREATED
        !(api.call(token, "GET", "/tenant/admin/users") as List).any { it.username == other.username }
    }

    def "a tenant admin cannot lock itself out: #change"() {
        given:
        def self = api.user(api.tenant().id, true)

        expect:
        api.status(self.token, "PUT", "/tenant/admin/users/${self.id}",
                [username: self.username, displayName: "Maria"] + body) == HttpStatus.CONFLICT
        api.status(self.token, "DELETE", "/tenant/admin/users/${self.id}") == HttpStatus.CONFLICT
        api.call(self.token, "GET", "/tenant/account").admin == true

        where:
        change                        | body
        "deactivating itself"         | [active: false]
        "taking its own rights away"  | [admin: false]
    }

    def "taking a tenant admin's rights away ends its admin API at once - in a group it still chats, in none it is out"() {
        given:
        def tenant = api.tenant()
        def demoted = api.user(tenant.id, true)
        def other = api.tenantAdminToken(tenant.id)

        expect:
        api.status(demoted.token, "GET", "/tenant/admin/users") == HttpStatus.OK

        when:
        api.call(other, "PUT", "/tenant/admin/users/${demoted.id}", [username: demoted.username, displayName: "Maria", admin: false,
                                                                     groupIds: [api.standardGroup(tenant.id)]])

        then: "the JWT still says tenant admin, the database no longer does"
        api.status(demoted.token, "GET", "/tenant/admin/users") == HttpStatus.FORBIDDEN
        api.status(demoted.token, "GET", "/tenant/account") == HttpStatus.OK

        when: "no longer a tenant admin, in no group"
        api.call(other, "PUT", "/tenant/admin/users/${demoted.id}", [username: demoted.username, displayName: "Maria", groupIds: []])

        then:
        api.status(demoted.token, "GET", "/tenant/account") == HttpStatus.FORBIDDEN
    }

    def "a tenant admin creates and revokes the client software's API keys of its own tenant"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)

        when:
        def created = api.call(token, "POST", "/tenant/admin/api-keys", [name: "Fachsoftware"])

        then:
        created.plainKey.startsWith("ai_")
        api.call(created.plainKey, "GET", "/api/v1/me").id == tenant.id
        (api.call(token, "GET", "/tenant/admin/api-keys") as List).collect { it.id } == [created.key.id]
        (api.admin("GET", "/admin/tenants/${tenant.id}/api-keys") as List).collect { it.id } == [created.key.id]

        and: "a name is required, and another tenant's admin can neither change nor revoke it"
        api.status(token, "POST", "/tenant/admin/api-keys", [name: " "]) == HttpStatus.BAD_REQUEST
        api.status(token, "PUT", "/tenant/admin/api-keys/${created.key.id}", [name: " "]) == HttpStatus.BAD_REQUEST
        def other = api.tenantAdminToken(api.tenant().id)
        api.status(other, "PUT", "/tenant/admin/api-keys/${created.key.id}", [keepsHistory: false]) == HttpStatus.NOT_FOUND
        api.status(other, "DELETE", "/tenant/admin/api-keys/${created.key.id}") == HttpStatus.NOT_FOUND

        when: "renamed and without history - left out stays as it is"
        def renamed = api.call(token, "PUT", "/tenant/admin/api-keys/${created.key.id}", [name: "Leitstand"])
        def switched = api.call(token, "PUT", "/tenant/admin/api-keys/${created.key.id}", [keepsHistory: false])

        then:
        renamed.name == "Leitstand"
        renamed.keepsHistory == true
        switched.name == "Leitstand"
        switched.keepsHistory == false
        (api.call(token, "GET", "/tenant/admin/api-keys") as List)[0].keepsHistory == false

        when:
        def status = api.status(token, "DELETE", "/tenant/admin/api-keys/${created.key.id}")

        then:
        status == HttpStatus.NO_CONTENT
        api.status(created.plainKey, "GET", "/api/v1/me") == HttpStatus.UNAUTHORIZED
    }

    def "the company's knowledge, kept by a tenant admin, is found by every user without seeing it"() {
        given:
        def tenant = api.tenant()
        def chat = api.chatModel()
        def embedding = api.embeddingModel()
        api.allow(tenant.id, chat, embedding)
        def token = api.tenantAdminToken(tenant.id)
        ["ANTHROPIC", "OPENAI"].each { api.call(token, "PUT", "/tenant/admin/provider-keys/${it}", [apiKey: "tenant-key-of-" + it]) }
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        def document = api.awaitDocument(token, api.upload(token, "werkstatt.txt", OPENING_HOURS).id)
        def user = api.userToken(tenant.id)

        expect:
        document.status == "READY"
        api.status(user, "GET", "/tenant/admin/documents") == HttpStatus.FORBIDDEN
        api.ask(user, [modelId: chat.id, message: "Wann ist die Werkstatt geöffnet?"]).sources == ["werkstatt.txt"]
        fake.chats.last().apiKey == "tenant-key-of-ANTHROPIC"

        and: "the admin sees the same list, the tenant admin the usage"
        (api.admin("GET", "/admin/tenants/${tenant.id}/documents") as List).collect { it.id } == [document.id]
        (api.call(token, "GET", "/tenant/admin/usage") as List).collect { it.kind }.toSet() == ["CHAT", "EMBEDDING"].toSet()
    }

    def "the admin keeps a tenant's provider keys too - its users' calls use them"() {
        given:
        def tenant = api.tenant()
        def chat = api.chatModel("GEMINI")
        api.allow(tenant.id, chat)

        when:
        def saved = api.admin("PUT", "/admin/tenants/${tenant.id}/provider-keys/GEMINI", [apiKey: "tenant-gemini-key-0001"])
        api.ask(api.userToken(tenant.id), [modelId: chat.id, message: "Hallo"])

        then:
        saved.keyHint == "…0001"
        fake.chats.last().apiKey == "tenant-gemini-key-0001"
        api.call(api.tenantAdminToken(tenant.id), "GET", "/tenant/admin/provider-keys") == [saved]
        api.status(api.adminToken, "GET", "/admin/tenants/999999999/provider-keys") == HttpStatus.NOT_FOUND

        when:
        def status = api.status(api.adminToken, "DELETE", "/admin/tenants/${tenant.id}/provider-keys/GEMINI")

        then:
        status == HttpStatus.NO_CONTENT
        api.admin("GET", "/admin/tenants/${tenant.id}/provider-keys") == []
    }
}
