package de.alpdesk.controller.tenant

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/** The admin assigns each tenant the models it may use; a tenant sees and uses only those (decided 6 October 2026). */
@MicronautTest(transactional = false)
class TenantModelSpec extends Specification {

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

    def "a new tenant may use no model; the admin assigns some, replaces them and removes them"() {
        given:
        def gemini = api.chatModel("GEMINI")
        def openai = api.chatModel("OPENAI")
        def tenant = api.tenant()

        expect:
        api.admin("GET", "/admin/tenants/${tenant.id}/models") == [modelIds: []]

        when:
        def assigned = api.admin("PUT", "/admin/tenants/${tenant.id}/models", [modelIds: [gemini.id, openai.id, gemini.id]])

        then: "each once"
        (assigned.modelIds as List).toSet() == [gemini.id, openai.id].toSet()
        (assigned.modelIds as List).size() == 2

        when:
        api.admin("PUT", "/admin/tenants/${tenant.id}/models", [modelIds: [gemini.id]])

        then:
        api.admin("GET", "/admin/tenants/${tenant.id}/models") == [modelIds: [gemini.id]]

        when:
        api.admin("PUT", "/admin/tenants/${tenant.id}/models", [modelIds: []])

        then:
        api.admin("GET", "/admin/tenants/${tenant.id}/models") == [modelIds: []]
    }

    def "invalid assignments are refused: #reason"() {
        given:
        def tenant = api.tenant()

        expect:
        api.status(api.adminToken, "PUT", "/admin/tenants/${tenant.id}/models", body) == HttpStatus.BAD_REQUEST

        where:
        reason          | body
        "no list"       | [:]
        "a null id"     | [modelIds: [null]]
        "unknown model" | [modelIds: [999999999]]
    }

    def "an unknown tenant answers 404"() {
        expect:
        api.status(api.adminToken, "GET", "/admin/tenants/999999999/models") == HttpStatus.NOT_FOUND
        api.status(api.adminToken, "PUT", "/admin/tenants/999999999/models", [modelIds: []]) == HttpStatus.NOT_FOUND
    }

    def "the model the tenant's documents are embedded with cannot be taken away"() {
        given:
        def embedding = api.embeddingModel()
        def chat = api.chatModel()
        def tenant = api.tenant()
        api.allow(tenant.id, embedding, chat)
        api.call(api.tenantAdminToken(tenant.id), "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])

        expect:
        api.status(api.adminToken, "PUT", "/admin/tenants/${tenant.id}/models", [modelIds: [chat.id]]) == HttpStatus.CONFLICT
        (api.admin("GET", "/admin/tenants/${tenant.id}/models").modelIds as List).toSet() == [embedding.id, chat.id].toSet()
    }

    def "tenant users and the client software see only the active models assigned to their tenant"() {
        given:
        def assigned = api.chatModel("GEMINI")
        def inactive = api.chatModel("GEMINI")
        def other = api.chatModel("GEMINI")
        def tenant = api.tenant()
        api.allow(tenant.id, assigned, inactive)
        api.admin("PUT", "/admin/models/${inactive.id}", inactive + [active: false])
        def key = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Software"]).plainKey

        expect:
        (api.call(api.tenantAdminToken(tenant.id), "GET", "/tenant/ai/models") as List).collect { it.id } == [assigned.id]
        (api.call(key, "GET", "/api/v1/models") as List).collect { it.id } == [assigned.id]

        and: "another tenant's assignment is its own"
        api.call(api.tenantAdminToken(api.tenant().id), "GET", "/tenant/ai/models") == []
        !(api.admin("GET", "/admin/tenants/${tenant.id}/models").modelIds as List).contains(other.id)
    }

    def "an image model is assigned like any other, but never chats nor embeds"() {
        given:
        def chat = api.chatModel("GEMINI")
        def image = api.imageModel()
        def tenant = api.tenant()
        api.allow(tenant.id, chat, image)
        def token = api.tenantAdminToken(tenant.id)
        def key = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Software"]).plainKey

        expect: "the tenant sees it with its kind"
        (api.call(token, "GET", "/tenant/ai/models") as List).find { it.id == image.id }.kind == "IMAGE"

        and: "the client software's chat models leave it out"
        (api.call(key, "GET", "/api/v1/models") as List).collect { it.id } == [chat.id]

        and: "it is neither the assistant's chat model nor the embedding model"
        api.status(token, "PUT", "/tenant/admin/assistant", [instructions: "", answerSource: "DOCUMENTS_ONLY",
                creativity: "MEDIUM", reasoning: "LOW", searchResults: 5, defaultModelId: image.id]) == HttpStatus.BAD_REQUEST
        api.status(token, "PUT", "/tenant/admin/embedding-model", [modelId: image.id]) == HttpStatus.BAD_REQUEST
    }

    def "deleting a model removes it from every tenant"() {
        given:
        def model = api.chatModel()
        def tenant = api.tenant()
        api.allow(tenant.id, model)

        when:
        api.status(api.adminToken, "DELETE", "/admin/models/${model.id}")

        then:
        api.admin("GET", "/admin/tenants/${tenant.id}/models") == [modelIds: []]
    }
}
