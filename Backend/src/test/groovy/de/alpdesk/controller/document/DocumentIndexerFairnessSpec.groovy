package de.alpdesk.controller.document

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * The indexer takes the tenants in turn (docs/architecture/knowledge-sources.md "The indexer, fair between tenants"):
 * one tenant's import of many documents holds up another tenant's single upload by a document, not by all of them;
 * within a tenant the order stays as queued.
 */
@MicronautTest(transactional = false)
class DocumentIndexerFairnessSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels models

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        models.reset()
    }

    def "another tenant's upload is embedded while a large import still waits"() {
        given: "every embedding takes a while"
        def large = adminWithModels(api.tenant())
        def small = adminWithModels(api.tenant())
        models.embeddingDelay = 150

        when: "one tenant imports 30 documents, then another uploads one"
        def files = api.importArchive(large, (1..30).collectEntries {
            ["dokument-${String.format('%02d', it)}.txt".toString(), "Dokument ${it} über die Werkstatt.".getBytes("UTF-8")]
        }) as List<Map>
        def single = api.upload(small, "einzeln.txt", "Ein einzelnes Dokument über das Lager.")
        def ready = api.awaitDocument(small, single.id)
        def largeNow = api.call(large, "GET", "/tenant/admin/documents") as List<Map>

        then: "the single one is ready while most of the import still waits"
        ready.status == "READY"
        largeNow.count { it.status == "READY" } < 15

        when: "the import ends"
        files.each { api.awaitDocument(large, it.documentId) }
        def order = models.embeddings.findAll { it.texts.any { text -> text =~ /Dokument \d+/ } }
                .collect { (it.texts.find { text -> text =~ /Dokument \d+/ } =~ /Dokument (\d+)/)[0][1] as int }

        then: "within the tenant in the order queued"
        order == (1..30).toList()
    }

    /** A tenant admin with an embedding model and the tenant's own OpenAI key: its token. */
    private String adminWithModels(Map tenant) {
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        token
    }
}
