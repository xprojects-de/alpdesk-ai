package de.alpdesk.controller.ai

import de.alpdesk.repositories.ai.ProviderKeyRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * The providers' API keys: the tenant's own come first; without one, the admin's global key - only for a tenant allowed
 * to, and then billed. A key is stored encrypted and never sent out again.
 */
@MicronautTest(transactional = false)
class ProviderKeySpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    ProviderKeyRepository keyRepository

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "a tenant user stores the tenant's key: only its last characters come back, the database holds it encrypted"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)

        when:
        def saved = api.call(token, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: " sk-proj-abcdefgh1234 "])

        then:
        saved == [provider: "OPENAI", keyHint: "…1234", updatedAt: saved.updatedAt]
        api.call(token, "GET", "/tenant/admin/provider-keys") == [saved]
        def stored = keyRepository.findByTenantIdAndProvider(tenant.id as Long, de.alpdesk.entities.ai.AiProvider.OPENAI).get()
        stored.apiKey().startsWith("enc:v1:")
        !stored.apiKey().contains("abcdefgh")

        and: "another user of the same tenant sees it, another tenant does not"
        api.call(api.tenantAdminToken(tenant.id), "GET", "/tenant/admin/provider-keys") == [saved]
        api.call(api.tenantAdminToken(api.tenant().id), "GET", "/tenant/admin/provider-keys") == []

        when:
        def status = api.status(token, "DELETE", "/tenant/admin/provider-keys/OPENAI")

        then:
        status == HttpStatus.NO_CONTENT
        api.call(token, "GET", "/tenant/admin/provider-keys") == []
        api.status(token, "DELETE", "/tenant/admin/provider-keys/OPENAI") == HttpStatus.NOT_FOUND
    }

    def "what is not a key is refused: #reason"() {
        given:
        def token = api.tenantAdminToken(api.tenant().id)

        expect:
        api.status(token, "PUT", path, [apiKey: key]) == HttpStatus.BAD_REQUEST

        where:
        reason             | path                              | key
        "empty"            | "/tenant/admin/provider-keys/OPENAI"    | ""
        "too short"        | "/tenant/admin/provider-keys/OPENAI"    | "sk-1"
        "with a space"     | "/tenant/admin/provider-keys/OPENAI"    | "sk-proj abcdefgh1234"
        "unknown provider" | "/tenant/admin/provider-keys/MISTRAL"   | "sk-proj-abcdefgh1234"
    }

    def "a call uses the tenant's own key before the admin's - and the global one only when allowed"() {
        given:
        def model = api.chatModel("GEMINI")
        api.admin("PUT", "/admin/provider-keys/GEMINI", [apiKey: "global-gemini-key-0001"])
        def allowed = api.tenant(true)
        def notAllowed = api.tenant(false)
        api.allow(allowed.id, model)
        api.allow(notAllowed.id, model)
        def allowedToken = api.tenantAdminToken(allowed.id)
        def notAllowedToken = api.tenantAdminToken(notAllowed.id)

        expect: "a tenant not allowed to use the global key has none"
        api.error(notAllowedToken, "POST", "/tenant/chat/stream", [modelId: model.id, message: "Hallo"]) == "No API key for GEMINI - a tenant admin adds one under API-Keys"

        when: "an allowed tenant without its own key"
        api.ask(allowedToken, [modelId: model.id, message: "Hallo"])

        then:
        fake.chats.last().apiKey == "global-gemini-key-0001"

        when: "it has its own key"
        api.call(allowedToken, "PUT", "/tenant/admin/provider-keys/GEMINI", [apiKey: "own-gemini-key-0002"])
        api.ask(allowedToken, [modelId: model.id, message: "Hallo"])

        then:
        fake.chats.last().apiKey == "own-gemini-key-0002"

        when: "the admin no longer allows the global key, the own key still works"
        api.admin("PUT", "/admin/tenants/${allowed.id}", [name: allowed.name, globalKeysAllowed: false])

        then:
        api.ask(allowedToken, [modelId: model.id, message: "Hallo"]).answer.role == "ASSISTANT"
    }

    def "once the admin no longer allows the global key, a tenant without its own key is refused before any call"() {
        given:
        def model = api.chatModel("GEMINI")
        api.admin("PUT", "/admin/provider-keys/GEMINI", [apiKey: "global-gemini-key-0003"])
        def tenant = api.tenant(true)
        api.allow(tenant.id, model)
        def token = api.tenantAdminToken(tenant.id)
        api.ask(token, [modelId: model.id, message: "Hallo"])
        def callsBefore = fake.chats.size()

        when:
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, globalKeysAllowed: false])

        then: "the admin's key is no longer billed to the tenant"
        api.error(token, "POST", "/tenant/chat/stream", [modelId: model.id, message: "Hallo"]) == "No API key for GEMINI - a tenant admin adds one under API-Keys"
        fake.chats.size() == callsBefore
    }

    def "the admin's global keys: listed with their hint, replaced, removed"() {
        when:
        api.admin("PUT", "/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-first-key-1111"])
        def replaced = api.admin("PUT", "/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-second-key-2222"])

        then:
        replaced.keyHint == "…2222"
        (api.admin("GET", "/admin/provider-keys") as List<Map>).findAll { it.provider == "ANTHROPIC" } == [replaced]
        !api.admin("GET", "/admin/provider-keys").toString().contains("second")

        when:
        api.status(api.adminToken, "DELETE", "/admin/provider-keys/ANTHROPIC")

        then:
        !(api.admin("GET", "/admin/provider-keys") as List<Map>).any { it.provider == "ANTHROPIC" }
    }

    def "a tenant's own key is not a global one, and tenant users do not reach the global keys"() {
        given:
        def token = api.tenantAdminToken(api.tenant().id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-tenant-only-9999"])

        expect:
        !api.admin("GET", "/admin/provider-keys").toString().contains("9999")
        api.status(token, "GET", "/admin/provider-keys") == HttpStatus.FORBIDDEN
    }
}
