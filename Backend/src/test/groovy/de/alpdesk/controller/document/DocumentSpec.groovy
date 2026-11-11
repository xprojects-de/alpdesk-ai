package de.alpdesk.controller.document

import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.document.DocumentIndexer
import de.alpdesk.services.tenant.TenantDatabases
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Named
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.sql.DataSource
import java.util.concurrent.ExecutorService
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A tenant's documents: uploaded by any of its users, embedded in the background with the tenant's embedding model,
 * deleted with their chunks - and embedded again when the model changes.
 */
@MicronautTest(transactional = false)
class DocumentSpec extends Specification {

    static final String OPERATING_RULES = "Betriebsordnung. Die Werkstatt ist täglich von 15 bis 21 Uhr geöffnet. " +
            "Das Mittagessen gibt es von 12 bis 13 Uhr in der Kantine. " * 40

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    TenantRepository tenants

    @Inject
    TenantDatabases databases

    @Inject
    DocumentIndexer indexer

    @Inject
    @Named("indexing")
    ExecutorService indexingExecutor

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "an uploaded document is embedded in the background and its usage recorded"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)

        when:
        def uploaded = api.upload(token, "betriebsordnung.txt", OPERATING_RULES)

        then:
        uploaded.fileName == "betriebsordnung.txt"
        uploaded.contentType.startsWith("text/plain")
        uploaded.sizeBytes == OPERATING_RULES.getBytes("UTF-8").length
        uploaded.status in ["PENDING", "PROCESSING", "READY"]

        when:
        def document = api.awaitDocument(token, uploaded.id)

        then:
        document.status == "READY"
        document.chunkCount > 1
        chunkCount(tenant, uploaded.id) == document.chunkCount
        fake.embeddings.sum { it.segments } == document.chunkCount

        and: "the tenant's other users see it too"
        (api.call(api.tenantAdminToken(tenant.id), "GET", "/tenant/admin/documents") as List).collect { it.id } == [uploaded.id]

        and: "the embedding is recorded as usage"
        def usage = api.call(token, "GET", "/tenant/admin/usage") as List<Map>
        usage.size() == 1
        usage[0].kind == "EMBEDDING"
        usage[0].keySource == "OWN"
        usage[0].inputTokens == document.chunkCount * 10
        usage[0].estimated == false
    }

    def "without a token count from the provider the usage is estimated from the text"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        fake.reportEmbeddingTokens = false

        when:
        api.awaitDocument(token, api.upload(token, "kurz.txt", "Die Halle ist ab 15 Uhr offen.").id)
        def usage = (api.call(token, "GET", "/tenant/admin/usage") as List<Map>)[0]

        then:
        usage.estimated == true
        usage.inputTokens == 8 // 30 characters / 4, rounded up
    }

    def "no upload without an embedding model or without a key for its provider"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)

        expect:
        api.uploadStatus(token, "a.txt", "Text") == HttpStatus.CONFLICT

        when:
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])

        then:
        api.uploadStatus(token, "a.txt", "Text") == HttpStatus.CONFLICT
    }

    def "a document without text is refused"() {
        given:
        def token = userWithModel(api.tenant())

        expect:
        api.uploadStatus(token, "leer.txt", "   ") == HttpStatus.BAD_REQUEST
    }

    def "a failing provider marks the document failed with the reason; a retry embeds it"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        fake.failures = 1

        when:
        def document = api.awaitDocument(token, api.upload(token, "a.txt", OPERATING_RULES).id)

        then:
        document.status == "FAILED"
        document.error == "OPENAI refused the API key - check it under API-Keys"
        chunkCount(tenant, document.id) == 0

        when:
        api.call(token, "POST", "/tenant/admin/documents/${document.id}/retry")

        then:
        api.awaitDocument(token, document.id).status == "READY"
    }

    def "deleting a document deletes its chunks"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        def kept = api.awaitDocument(token, api.upload(token, "bleibt.txt", OPERATING_RULES).id)
        def deleted = api.awaitDocument(token, api.upload(token, "weg.txt", OPERATING_RULES).id)

        when:
        def status = api.status(token, "DELETE", "/tenant/admin/documents/${deleted.id}")

        then:
        status == HttpStatus.NO_CONTENT
        chunkCount(tenant, deleted.id) == 0
        chunkCount(tenant, kept.id) == kept.chunkCount
        api.status(token, "DELETE", "/tenant/admin/documents/${deleted.id}") == HttpStatus.NOT_FOUND
    }

    def "another embedding model embeds every document again with its vector length"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        def document = api.awaitDocument(token, api.upload(token, "a.txt", OPERATING_RULES).id)
        def larger = api.embeddingModel(128)
        api.allow(tenant.id, larger)

        when:
        def settings = api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: larger.id])

        then:
        settings.embeddingModelId == larger.id
        api.awaitDocument(token, document.id).status == "READY"
        vectorLength(tenant) == 128
        chunkCount(tenant, document.id) == document.chunkCount
        fake.embeddings.last().model == larger.modelName
    }

    def "a model change whose chunks cannot be dropped keeps the previous model; the next attempt changes it"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        def document = api.awaitDocument(token, api.upload(token, "a.txt", OPERATING_RULES).id)
        def previousModelId = embeddingModelId(tenant)
        def larger = api.embeddingModel(128)
        api.allow(tenant.id, larger)

        when: "another connection holds the chunks' table past the lock timeout"
        def status = holdingEmbeddingTable(tenant) {
            api.status(token, "PUT", "/tenant/admin/embedding-model", [modelId: larger.id])
        }

        then: "the tenant keeps the previous model with its chunks"
        status == HttpStatus.SERVICE_UNAVAILABLE
        embeddingModelId(tenant) == previousModelId
        api.awaitDocument(token, document.id).status == "READY"
        vectorLength(tenant) == 64

        when:
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: larger.id])

        then:
        embeddingModelId(tenant) == larger.id
        api.awaitDocument(token, document.id).status == "READY"
        vectorLength(tenant) == 128
        chunkCount(tenant, document.id) == document.chunkCount
    }

    def "a model change without a key for the new model is refused and keeps the previous model with its chunks"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        def document = api.awaitDocument(token, api.upload(token, "a.txt", OPERATING_RULES).id)
        def previousModelId = embeddingModelId(tenant)
        def gemini = api.admin("POST", "/admin/models", [provider: "GEMINI", kind: "EMBEDDING",
                                                         modelName: "embed-" + AiApi.unique(), displayName: "Gemini " + AiApi.unique(),
                                                         dimension: 128, inputPrice: 0.02, outputPrice: 0]) as Map
        api.allow(tenant.id, gemini)

        when: "the tenant has an OpenAI key only"
        def status = api.status(token, "PUT", "/tenant/admin/embedding-model", [modelId: gemini.id])

        then: "otherwise every document would end failed and the chat find nothing"
        status == HttpStatus.CONFLICT
        embeddingModelId(tenant) == previousModelId
        api.awaitDocument(token, document.id).status == "READY"
        chunkCount(tenant, document.id) == document.chunkCount
    }

    def "a document queued twice is embedded once"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        def document = api.awaitDocument(token, api.upload(token, "a.txt", OPERATING_RULES).id)
        def callsBefore = fake.embeddings.size()

        when: "a run left over from a model change finds it done"
        indexer.submit(tenant.id as Long, document.id as Long)
        awaitIndexingIdle()

        then:
        fake.embeddings.size() == callsBefore
        api.awaitDocument(token, document.id).status == "READY"
        chunkCount(tenant, document.id) == document.chunkCount
    }

    def "documents left waiting or half embedded when the server stopped are embedded on start"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        def waiting = api.awaitDocument(token, api.upload(token, "wartet.txt", OPERATING_RULES).id)
        def halfDone = api.awaitDocument(token, api.upload(token, "halb.txt", OPERATING_RULES).id)
        execute(tenant, "UPDATE document SET status = 'PENDING', chunk_count = 0 WHERE id = ${waiting.id}")
        execute(tenant, "DELETE FROM embedding WHERE metadata->>'document_id' = '${waiting.id}'")
        execute(tenant, "UPDATE document SET status = 'PROCESSING', chunk_count = 0 WHERE id = ${halfDone.id}")
        execute(tenant, "DELETE FROM embedding WHERE ctid IN (SELECT ctid FROM embedding " +
                "WHERE metadata->>'document_id' = '${halfDone.id}' LIMIT 1)")

        when:
        indexer.onApplicationEvent(null)

        then: "each is embedded once more, without the pieces of its broken-off run"
        api.awaitDocument(token, waiting.id).status == "READY"
        api.awaitDocument(token, halfDone.id).status == "READY"
        chunkCount(tenant, waiting.id) == waiting.chunkCount
        chunkCount(tenant, halfDone.id) == halfDone.chunkCount
    }

    def "the look for unfinished documents on start keeps no connection to a tenant database open"() {
        given: "a tenant with an embedding model whose database nothing has opened yet"
        def tenant = api.tenant()
        tenants.updateEmbeddingModelId(tenant.id as Long, api.embeddingModel().id as Long)

        when: "the start's look at every tenant - unfinished documents and the full-text index - has run"
        indexer.onApplicationEvent(null)
        awaitIndexingIdle()

        then: "hundreds of tenants would otherwise take the server's last connections on every start"
        otherConnections(tenant) == 0
    }

    def "a run that cannot reach the tenant's database is an error in the application log"() {
        given: "a tenant with an embedding model whose database is gone"
        def tenant = api.tenant()
        tenants.updateEmbeddingModelId(tenant.id as Long, api.embeddingModel().id as Long)
        def databaseName = tenants.findById(tenant.id as Long).get().databaseName()
        databases.drop(databaseName)

        when:
        indexer.submit(tenant.id as Long, 1L)

        then: "before, the exception left the run and reached only stderr - not the admin's log and daily report"
        new PollingConditions(timeout: 10).eventually {
            assert RecentLogAppender.installed().entries().any {
                it.logger() == DocumentIndexer.name && it.level() == "ERROR" &&
                        it.message().startsWith("Document 1 of tenant ${tenant.id} could not be embedded")
            }
        }

        cleanup: "the database back, so a later start in this context finds nothing missing"
        databases.ensure(databaseName)
    }

    def "only an active embedding model assigned to the tenant can be chosen"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)
        def chat = api.chatModel()
        def notAssigned = api.embeddingModel()
        api.allow(tenant.id, chat)

        expect:
        api.status(token, "PUT", "/tenant/admin/embedding-model", [modelId: notAssigned.id]) == HttpStatus.BAD_REQUEST
        api.status(token, "PUT", "/tenant/admin/embedding-model", [modelId: chat.id]) == HttpStatus.BAD_REQUEST
        api.status(token, "PUT", "/tenant/admin/embedding-model", [modelId: 999999999]) == HttpStatus.BAD_REQUEST
    }

    def "documents belong to their tenant"() {
        given:
        def owner = api.tenant()
        def ownerToken = userWithModel(owner)
        def document = api.upload(ownerToken, "a.txt", OPERATING_RULES)
        def otherToken = userWithModel(api.tenant())

        expect:
        api.call(otherToken, "GET", "/tenant/admin/documents") == []
        api.status(otherToken, "DELETE", "/tenant/admin/documents/${document.id}") == HttpStatus.NOT_FOUND
        api.awaitDocument(ownerToken, document.id).status == "READY"
    }

    /** A user of the tenant with an embedding model and the tenant's own OpenAI key: its token. */
    private String userWithModel(Map tenant) {
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        token
    }

    /**
     * Waits until the indexing executor has run everything queued before - a run that skips leaves no trace to wait for.
     * Its two threads (micronaut.executors.indexing.n-threads) meet at a barrier only when both are free.
     */
    private void awaitIndexingIdle() {
        def barrier = new CyclicBarrier(2)
        def bothFree = (1..2).collect { indexingExecutor.submit { barrier.await(10, TimeUnit.SECONDS) } as Future }
        bothFree*.get(10, TimeUnit.SECONDS)
    }

    private int chunkCount(Map tenant, Object documentId) {
        query(tenant, "SELECT COUNT(*) FROM embedding WHERE metadata->>'document_id' = '${documentId}'") as int
    }

    private int vectorLength(Map tenant) {
        query(tenant, "SELECT vector_dims(embedding) FROM embedding LIMIT 1") as int
    }

    private Long embeddingModelId(Map tenant) {
        tenants.findById(tenant.id as Long).get().embeddingModelId()
    }

    /** Runs the action while another connection holds a lock on the chunks' table, as a long search would. */
    private <T> T holdingEmbeddingTable(Map tenant, Closure<T> action) {
        dataSource(tenant).connection.withCloseable { connection ->
            connection.autoCommit = false
            connection.createStatement().execute("LOCK TABLE embedding IN ACCESS SHARE MODE")
            try {
                action()
            } finally {
                connection.rollback()
            }
        }
    }

    private void execute(Map tenant, String sql) {
        dataSource(tenant).connection.withCloseable { connection -> connection.createStatement().execute(sql) }
    }

    /** The connections to the tenant's database other than the one asking. */
    private int otherConnections(Map tenant) {
        databases.openConnection(tenants.findById(tenant.id as Long).get().databaseName()).withCloseable { connection ->
            def result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND pid <> pg_backend_pid()")
            result.next()
            result.getInt(1)
        }
    }

    private DataSource dataSource(Map tenant) {
        databases.dataSource(tenants.findById(tenant.id as Long).get().databaseName())
    }

    private Object query(Map tenant, String sql) {
        dataSource(tenant).connection.withCloseable { connection ->
            def result = connection.createStatement().executeQuery(sql)
            result.next() ? result.getObject(1) : null
        }
    }
}
