package de.alpdesk.controller.document

import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.document.KnowledgeScope
import de.alpdesk.services.document.KnowledgeSearch
import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * app.search.rerank-candidates (docs/architecture/ai-features.md "Reranking"): a server without a GPU gives the
 * reranker fewer of the search's candidates - it takes about a second for each on a CPU.
 */
@MicronautTest(transactional = false)
@Property(name = "app.search.rerank-candidates", value = "3")
class RerankCandidatesSpec extends Specification {

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

    def "the reranker gets only the best candidates of the setting - and never fewer than the results asked for"() {
        given:
        def tenant = api.tenant()
        def embedding = api.embeddingModel()
        def reranker = api.rerankModel()
        api.allow(tenant.id, embedding, reranker)
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "own-key-of-OPENAI"])
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        (1..6).each { api.awaitDocument(admin, api.upload(admin, "kessel-${it}.txt", "Der Kessel wird gewartet, Nummer ${it}.").id) }
        api.call(admin, "PUT", "/tenant/admin/reranking-model", [modelId: reranker.id])
        def entity = tenants.findById(tenant.id as Long).get()

        when:
        def hits = knowledge.search(entity, null, KnowledgeScope.everything(), "Wann wird der Kessel gewartet?", results)

        then:
        (fake.reranks.first().texts as List).size() == reranked
        hits.size() == results

        where:
        results || reranked
        2       || 3
        5       || 5
    }

    def "a number outside 2 to 40 stops the start: #candidates"() {
        when:
        new KnowledgeSearch(null, null, null, null, null, KnowledgeSearch.Mode.HYBRID, 0.3d, candidates)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "app.search.rerank-candidates must be between 2 and 40"

        where:
        candidates << [1, 41]
    }
}
