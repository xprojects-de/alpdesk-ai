package de.alpdesk.repositories.document

import de.alpdesk.entities.document.DocumentOrigin
import de.alpdesk.entities.document.DocumentStatus
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.tenant.TenantDatabases
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * The image model's reading of a scan is stored only on the version that was read: a page takes a model call, a scan
 * minutes - meanwhile the client software may push a new version under the same key.
 */
@MicronautTest(transactional = false)
class DocumentReadingSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    DocumentRepository documents

    @Inject
    TenantRepository tenants

    @Inject
    TenantDatabases databases

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
    }

    def "a reading is stored on the version that was read - not on one that replaced it meanwhile"() {
        given: "a scan pushed under a key, read as version 1"
        def dataSource = databases.dataSource(tenants.findById(api.tenant().id as Long).get().databaseName())
        def first = documents.upsertPushed(dataSource, scan("hash-1"), false, null).get().document()

        when: "version 2 is pushed while version 1 is read"
        documents.upsertPushed(dataSource, scan("hash-2"), false, null)
        def stored = documents.saveReading(dataSource, first.id(), "hash-1", "Gelesen: Version 1")

        then: "the old reading is dropped; version 2 still waits for its own"
        !stored
        documents.findText(dataSource, first.id(), "hash-2").get() == "Text 2"
        documents.findById(dataSource, first.id()).get().readsImages()

        when: "version 2's reading"
        def storedSecond = documents.saveReading(dataSource, first.id(), "hash-2", "Gelesen: Version 2")

        then:
        storedSecond
        documents.findText(dataSource, first.id(), "hash-2").get() == "Gelesen: Version 2"
        !documents.findById(dataSource, first.id()).get().readsImages()
    }

    def "a run reads the text of its own version only - not of one pushed since it looked the document up"() {
        given: "version 1 pushed, its run has looked it up"
        def dataSource = databases.dataSource(tenants.findById(api.tenant().id as Long).get().databaseName())
        def first = documents.upsertPushed(dataSource, scan("hash-1"), false, null).get().document()

        when: "version 2 is pushed before the run reads the text"
        documents.upsertPushed(dataSource, scan("hash-2"), false, null)

        then: "version 1's run gets nothing - else it would embed version 2's text and mark it READY as version 1"
        documents.findText(dataSource, first.id(), "hash-1").isEmpty()
        documents.findText(dataSource, first.id(), "hash-2").get() == "Text 2"
    }

    def "a document is READY only as the version that was embedded - one replaced meanwhile stays waiting"() {
        given: "version 1 pushed and being embedded"
        def dataSource = databases.dataSource(tenants.findById(api.tenant().id as Long).get().databaseName())
        def first = documents.upsertPushed(dataSource, scan("hash-1"), false, null).get().document()

        when: "version 2 is pushed before version 1's run ends"
        documents.upsertPushed(dataSource, scan("hash-2"), false, null)
        def readyAsFirst = documents.markReady(dataSource, first.id(), "hash-1", 3)

        then: "version 1's run does not make version 2 READY - the search would find version 1's chunks"
        !readyAsFirst
        documents.findById(dataSource, first.id()).get().status() == DocumentStatus.PENDING

        when:
        def readyAsSecond = documents.markReady(dataSource, first.id(), "hash-2", 4)

        then:
        readyAsSecond
        documents.findById(dataSource, first.id()).get().status() == DocumentStatus.READY
        documents.findById(dataSource, first.id()).get().chunkCount() == 4
    }

    private static DocumentRepository.NewDocument scan(String hash) {
        new DocumentRepository.NewDocument("scan.pdf", "", "application/pdf", 100, "Text " + hash.takeRight(1), true,
                null, DocumentOrigin.API, "scan-1", hash, null, null)
    }
}
