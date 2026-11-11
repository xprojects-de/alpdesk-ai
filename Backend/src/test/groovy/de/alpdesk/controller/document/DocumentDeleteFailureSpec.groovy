package de.alpdesk.controller.document

import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.document.TenantEmbeddings
import de.alpdesk.services.tenant.TenantDatabases
import de.alpdesk.services.tenant.TenantService
import de.alpdesk.storage.LocalFileStorage
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Replaces
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import spock.lang.Shared
import spock.lang.Specification

import java.sql.SQLException

/**
 * A document is deleted with its chunks (docs/privacy.md) - also when its original file cannot be removed: the file
 * stays behind unreachable, the chunks holding its text do not. Chunks that cannot be removed do not stop the deletion
 * either: the row is gone, and the original file goes all the same.
 */
@MicronautTest(transactional = false)
@Property(name = "spec.name", value = "DocumentDeleteFailureSpec")
class DocumentDeleteFailureSpec extends Specification {

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
    FailingStorage storage

    @Inject
    FailingEmbeddings embeddings

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
        storage.deleted.clear()
    }

    def cleanup() {
        storage.failDelete = false
        embeddings.failRemove = false
    }

    def "a document whose file cannot be removed is deleted with its chunks"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        def document = api.awaitDocument(token, api.upload(token, "weg.txt", DocumentSpec.OPERATING_RULES).id)
        storage.failDelete = true

        when:
        def status = api.status(token, "DELETE", "/tenant/admin/documents/${document.id}")

        then:
        status == HttpStatus.NO_CONTENT
        chunkCount(tenant, document.id) == 0
        api.call(token, "GET", "/tenant/admin/documents") == []
    }

    def "deleting the uploads at once removes every chunk although no file can be removed"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        def first = api.awaitDocument(token, api.upload(token, "a.txt", DocumentSpec.OPERATING_RULES).id)
        def second = api.awaitDocument(token, api.upload(token, "b.txt", "Die Halle ist ab 15 Uhr offen.").id)
        storage.failDelete = true

        when:
        def deleted = api.call(token, "DELETE", "/tenant/admin/documents/uploads?area=company") as Map

        then:
        deleted.deleted == 2
        chunkCount(tenant, first.id) == 0
        chunkCount(tenant, second.id) == 0
    }

    def "a document whose chunks cannot be removed is deleted with its file"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        def document = api.awaitDocument(token, api.upload(token, "weg.txt", DocumentSpec.OPERATING_RULES).id)
        embeddings.failRemove = true

        when:
        def status = api.status(token, "DELETE", "/tenant/admin/documents/${document.id}")

        then:
        status == HttpStatus.NO_CONTENT
        api.call(token, "GET", "/tenant/admin/documents") == []
        storage.deleted.contains(originalKey(tenant, document.id))
    }

    def "deleting the uploads at once removes every file although no chunk can be removed"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)
        def first = api.awaitDocument(token, api.upload(token, "a.txt", DocumentSpec.OPERATING_RULES).id)
        def second = api.awaitDocument(token, api.upload(token, "b.txt", "Die Halle ist ab 15 Uhr offen.").id)
        embeddings.failRemove = true

        when:
        def deleted = api.call(token, "DELETE", "/tenant/admin/documents/uploads?area=company") as Map

        then:
        deleted.deleted == 2
        storage.deleted.containsAll([originalKey(tenant, first.id), originalKey(tenant, second.id)])
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

    private int chunkCount(Map tenant, Object documentId) {
        def databaseName = tenants.findById(tenant.id as Long).get().databaseName()
        databases.dataSource(databaseName).connection.withCloseable { connection ->
            def result = connection.createStatement()
                    .executeQuery("SELECT COUNT(*) FROM embedding WHERE metadata->>'document_id' = '${documentId}'")
            result.next()
            result.getInt(1)
        }
    }

    /** Where the document's original file is kept - asked to be deleted even when there is none (a text file). */
    private static String originalKey(Map tenant, Object documentId) {
        TenantService.storagePrefix(tenant.id as Long) + "documents/" + documentId
    }

    /** The real storage, failing to delete on demand; it remembers what it was asked to delete. */
    @Singleton
    @Replaces(LocalFileStorage)
    @Requires(property = "spec.name", value = "DocumentDeleteFailureSpec")
    static class FailingStorage extends LocalFileStorage {

        volatile boolean failDelete
        final List<String> deleted = Collections.synchronizedList([])

        FailingStorage(@Value('${app.storage.local.path}') String path) {
            super(path)
        }

        @Override
        void delete(String key) {
            deleted << key
            if (failDelete) {
                throw new UncheckedIOException("Could not delete file " + key, new IOException("permission denied"))
            }
            super.delete(key)
        }
    }

    /** The real chunk store, failing to remove chunks on demand. */
    @Singleton
    @Replaces(TenantEmbeddings)
    @Requires(property = "spec.name", value = "DocumentDeleteFailureSpec")
    static class FailingEmbeddings extends TenantEmbeddings {

        volatile boolean failRemove

        FailingEmbeddings(TenantDatabases databases) {
            super(databases)
        }

        @Override
        void removeDocument(String databaseName, int dimension, Long documentId) {
            failIfAsked()
            super.removeDocument(databaseName, dimension, documentId)
        }

        @Override
        void removeDocuments(String databaseName, List<Long> documentIds) {
            failIfAsked()
            super.removeDocuments(databaseName, documentIds)
        }

        private void failIfAsked() {
            if (failRemove) {
                throw new IllegalStateException("Could not remove the chunks", new SQLException("connection lost"))
            }
        }
    }
}
