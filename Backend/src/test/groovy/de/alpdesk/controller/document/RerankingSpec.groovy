package de.alpdesk.controller.document

import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.document.KnowledgeScope
import de.alpdesk.services.document.KnowledgeSearch
import dev.langchain4j.data.message.ToolExecutionResultMessage
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * Reranking (docs/architecture/ai-features.md "Reranking"): a reranker of the catalog (TEI) is chosen per tenant like
 * the embedding model - off unless chosen. With one, the search's merged candidates are reordered by it before the best
 * are returned; a failing reranker keeps the search's own order.
 */
@MicronautTest(transactional = false)
class RerankingSpec extends Specification {

    static final String QUESTION = "Wie oft wird der Kessel gewartet?"

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    KnowledgeSearch knowledge

    @Inject
    TenantRepository tenants

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "a tenant admin switches the reranker on and off - every user's settings show it"() {
        given:
        def tenant = api.tenant()
        def reranker = api.rerankModel()
        api.allow(tenant.id, reranker)
        def admin = api.tenantAdminToken(tenant.id)
        def user = api.userToken(tenant.id)

        expect: "off unless chosen"
        (api.call(user, "GET", "/tenant/ai/settings") as Map).rerankingModelId == null

        when:
        def switchedOn = api.call(admin, "PUT", "/tenant/admin/reranking-model", [modelId: reranker.id]) as Map

        then:
        switchedOn.rerankingModelId == reranker.id
        (api.call(user, "GET", "/tenant/ai/settings") as Map).rerankingModelId == reranker.id

        when:
        def switchedOff = api.call(admin, "PUT", "/tenant/admin/reranking-model", [modelId: null]) as Map

        then:
        switchedOff.rerankingModelId == null
        (api.call(user, "GET", "/tenant/ai/settings") as Map).rerankingModelId == null
    }

    def "the admin switches it for a tenant - only for that one"() {
        given:
        def tenant = api.tenant()
        def other = api.tenant()
        def reranker = api.rerankModel()
        api.allow(tenant.id, reranker)
        api.allow(other.id, reranker)

        when:
        def updated = api.admin("PUT", "/admin/tenants/${tenant.id}/reranking-model", [modelId: reranker.id]) as Map

        then:
        updated.rerankingModelId == reranker.id
        (api.admin("GET", "/admin/tenants/${tenant.id}") as Map).rerankingModelId == reranker.id
        (api.admin("GET", "/admin/tenants/${other.id}") as Map).rerankingModelId == null

        and: "an unknown tenant is not found"
        api.status(api.adminToken, "PUT", "/admin/tenants/999999999/reranking-model", [modelId: reranker.id]) == HttpStatus.NOT_FOUND
    }

    def "only an active reranker assigned to the tenant can be chosen: #refused"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def model = modelFor(refused, tenant)

        expect:
        api.status(admin, "PUT", "/tenant/admin/reranking-model", [modelId: model]) == HttpStatus.BAD_REQUEST
        api.error(admin, "PUT", "/tenant/admin/reranking-model", [modelId: model]) == "Unknown or unavailable rerank model"
        (api.call(admin, "GET", "/tenant/ai/settings") as Map).rerankingModelId == null

        where:
        refused << ["unknown", "not assigned", "deactivated", "an embedding model", "assigned to another tenant only"]
    }

    def "a reranker is a TEI server's, and a TEI server offers only the reranker: #model"() {
        expect:
        api.status(api.adminToken, "POST", "/admin/models", [modelName: "m-" + AiApi.unique(),
                displayName: "M " + AiApi.unique(), inputPrice: 0, outputPrice: 0] + model) == status

        where:
        model                                                                              || status
        [provider: "TEI", kind: "RERANK", baseUrl: "https://reranker.example.com"]         || HttpStatus.CREATED
        [provider: "TEI", kind: "CHAT", baseUrl: "https://reranker.example.com"]           || HttpStatus.BAD_REQUEST
        [provider: "OLLAMA", kind: "RERANK", baseUrl: "https://reranker.example.com"]      || HttpStatus.BAD_REQUEST
        [provider: "OPENAI", kind: "RERANK"]                                               || HttpStatus.BAD_REQUEST
        [provider: "TEI", kind: "RERANK"]                                                  || HttpStatus.BAD_REQUEST
        [provider: "TEI", kind: "RERANK", baseUrl: "http://reranker.example.com"]          || HttpStatus.BAD_REQUEST
        [provider: "TEI", kind: "RERANK", baseUrl: "https://reranker.example.com", dimension: 8] || HttpStatus.BAD_REQUEST
    }

    def "taking the reranker from the tenant, or deleting it, switches reranking off"() {
        given:
        def tenant = api.tenant()
        def chat = api.chatModel()
        def reranker = api.rerankModel()
        api.allow(tenant.id, chat, reranker)
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/reranking-model", [modelId: reranker.id])

        when: "the admin leaves it out of the tenant's models"
        api.admin("PUT", "/admin/tenants/${tenant.id}/models", [modelIds: [chat.id]])

        then:
        (api.call(admin, "GET", "/tenant/ai/settings") as Map).rerankingModelId == null

        when: "it is chosen again and the model is deleted"
        api.allow(tenant.id, reranker)
        api.call(admin, "PUT", "/tenant/admin/reranking-model", [modelId: reranker.id])
        api.status(api.adminToken, "DELETE", "/admin/models/${reranker.id}") == HttpStatus.NO_CONTENT

        then:
        (api.call(admin, "GET", "/tenant/ai/settings") as Map).rerankingModelId == null
    }

    def "with a reranker the search returns the candidates in its order - its call billed, tokens estimated"() {
        given: "the favourite shares one word with the question only - the search alone places it last"
        def setup = tenantWithDocuments()

        expect:
        !search(setup.tenant)*.fileName().take(5).contains("favorit.txt")
        fake.reranks.isEmpty()

        when:
        api.call(setup.admin, "PUT", "/tenant/admin/reranking-model", [modelId: setup.reranker.id])
        def hits = search(setup.tenant)

        then: "the reranker read every candidate with the question, and its best came first"
        fake.reranks.size() == 1
        fake.reranks.first().query == QUESTION
        fake.reranks.first().apiKey == null
        (fake.reranks.first().texts as List).size() == 7
        hits.size() == 5
        hits.first().fileName() == "favorit.txt"

        and: "billed as the tenant's usage of the reranker, estimated from the texts' length"
        def usage = (api.admin("GET", "/admin/usage?tenantId=${setup.tenant.id}") as List<Map>).find { it.kind == "RERANK" }
        usage.calls == 1
        usage.inputTokens > 0
        usage.keySource == "GLOBAL"
    }

    def "the chat's search uses the reranker too"() {
        given:
        def setup = tenantWithDocuments()
        api.call(setup.admin, "PUT", "/tenant/admin/reranking-model", [modelId: setup.reranker.id])

        when:
        api.ask(api.userToken(setup.tenant.id), [modelId: setup.chat.id, message: QUESTION])

        then: "the excerpts the model got start with the reranker's best"
        fake.reranks.size() == 1
        def excerpts = fake.chats[1].messages.find {
            it instanceof ToolExecutionResultMessage && it.toolName() == "similarity_search"
        }.text() as String
        excerpts.indexOf("favorit.txt") >= 0
        excerpts.indexOf("favorit.txt") < excerpts.indexOf("wartung-")
    }

    def "a failing reranker keeps the search's own order and says so in the log - nothing billed"() {
        given:
        def setup = tenantWithDocuments()
        api.call(setup.admin, "PUT", "/tenant/admin/reranking-model", [modelId: setup.reranker.id])
        fake.rerankFails = true

        when:
        def hits = search(setup.tenant)

        then:
        hits.size() == 5
        !hits*.fileName().contains("favorit.txt")
        RecentLogAppender.installed().entries().any {
            it.message() == "Reranking for tenant ${setup.tenant.id} failed, the search keeps its own order: " +
                    "${setup.reranker.displayName} is not reachable - please try again later"
        }
        !(api.admin("GET", "/admin/usage?tenantId=${setup.tenant.id}") as List<Map>).any { it.kind == "RERANK" }
    }

    def "a deactivated reranker is not asked"() {
        given:
        def setup = tenantWithDocuments()
        api.call(setup.admin, "PUT", "/tenant/admin/reranking-model", [modelId: setup.reranker.id])
        api.admin("PUT", "/admin/models/${setup.reranker.id}", setup.reranker + [active: false])

        when:
        def hits = search(setup.tenant)

        then:
        fake.reranks.isEmpty()
        hits.size() == 5
    }

    /**
     * A tenant with a chat model, an embedding model, a reranker assigned (not chosen) and seven documents: six on the
     * Kessel's maintenance, the favourite of the reranker's fake sharing one word with the question.
     */
    private Map tenantWithDocuments() {
        def tenant = api.tenant()
        def chat = api.chatModel()
        def embedding = api.embeddingModel()
        def reranker = api.rerankModel()
        api.allow(tenant.id, chat, embedding, reranker)
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-key-of-ANTHROPIC"])
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "own-key-of-OPENAI"])
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        (1..6).each {
            api.awaitDocument(admin, api.upload(admin, "wartung-${it}.txt",
                    "Wie oft wird der Kessel gewartet? Der Kessel wird gewartet, Kessel Nummer ${it}.").id)
        }
        api.awaitDocument(admin, api.upload(admin, "favorit.txt",
                "${FakeAiModels.RERANK_FAVOURITE}: einmal im Jahr, im Herbst, prüft der Monteur den Kessel.").id)
        [tenant: tenant, admin: admin, chat: chat, reranker: reranker]
    }

    private List<KnowledgeSearch.Hit> search(Map tenant) {
        knowledge.search(tenants.findById(tenant.id as Long).get(), null, KnowledgeScope.everything(), QUESTION,
                KnowledgeSearch.Mode.HYBRID)
    }

    private Object modelFor(String refused, Map tenant) {
        switch (refused) {
            case "unknown":
                return 999_999_999
            case "not assigned":
                return api.rerankModel().id
            case "deactivated":
                def model = api.rerankModel()
                api.allow(tenant.id, model)
                api.admin("PUT", "/admin/models/${model.id}", model + [active: false])
                return model.id
            case "an embedding model":
                def model = api.embeddingModel()
                api.allow(tenant.id, model)
                return model.id
            case "assigned to another tenant only":
                def model = api.rerankModel()
                api.allow(api.tenant().id, model)
                return model.id
        }
    }
}
