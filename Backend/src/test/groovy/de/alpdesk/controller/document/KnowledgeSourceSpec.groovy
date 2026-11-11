package de.alpdesk.controller.document

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.document.SourceApi
import de.alpdesk.services.document.SourceSync
import de.alpdesk.services.task.FakeStoreClient
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant
import java.util.concurrent.CountDownLatch

import static de.alpdesk.services.document.SourceApi.SOURCES

/**
 * Knowledge sources (docs/architecture/knowledge-sources.md) over HTTP: a folder of a file store - subfolders too -
 * becomes documents; saving only lists, a run by hand reads everything open; changed files are embedded again, touched
 * ones not, vanished ones deleted; refused files are remembered; a store out of reach deletes nothing; documents may
 * be deleted and moved by hand; a source is removed with its documents or keeps them. Through FakeStoreClient.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
class KnowledgeSourceSpec extends Specification {

    static final String TEXT = "Die Kurveneinleitung beginnt mit dem Umkanten. "

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels models

    @Inject
    FakeStoreClient fake

    @Inject
    SourceSync sync

    @Shared
    AiApi api

    SourceApi sources

    def setup() {
        api = api ?: new AiApi(client)
        sources = new SourceApi(api, fake)
        models.reset()
        fake.reset()
    }

    def "saving lists the tree without reading it; a run by hand reads every file - in subfolders too - into the knowledge"() {
        given: "a folder with subfolders, a hidden file, a hidden folder, an image and a type that is not read"
        def setup = sources.tenant()
        String base = setup.base
        sources.write("${base}/handbuch.md", "# Handbuch\n" + TEXT * 5)
        sources.write("${base}/Projekte/2025/angebot.txt", "Angebot " + TEXT * 5)
        sources.write("${base}/Projekte/notiz.LOG", "Notiz " + TEXT * 5)
        sources.write("${base}/.DS_Store", "x")
        sources.write("${base}/.vorschau/handbuch.md/thumb.txt", "x")
        sources.write("${base}/foto.jpg", "not read without an image model")
        sources.write("${base}/archiv.zip", "not read")

        when: "the source is saved"
        def source = sources.source(setup)

        then: "only listed: three files open, two skipped by their type, nothing read"
        source.openFiles == 3
        source.openBytes > 0
        source.lastSkipped == 2
        source.lastAdded == 0
        source.lastError == null
        source.lastSucceededAt != null
        sources.documents(setup.token, source.id).isEmpty()
        models.embeddings.isEmpty()

        when: "a run by hand"
        def done = sources.run(setup.token, source.id)
        def documents = sources.documents(setup.token, source.id)

        then: "every file is a document with its folder, kept by the source"
        done.lastAdded == 3
        done.openFiles == 0
        documents.keySet() == ["handbuch.md", "Projekte/2025/angebot.txt", "Projekte/notiz.LOG"] as Set
        documents.values().every { it.origin == "SOURCE" && it.status == "READY" && it.areaId == null }
        documents["Projekte/2025/angebot.txt"].folder == "Projekte/2025"
    }

    def "a changed file is embedded again, a touched one not, a vanished one is deleted"() {
        given:
        def setup = sources.tenant()
        String base = setup.base
        sources.write("${base}/a.txt", "Erste Fassung. " + TEXT * 5)
        sources.write("${base}/b.txt", "Bleibt gleich. " + TEXT * 5)
        sources.write("${base}/c.txt", "Verschwindet. " + TEXT * 5)
        def source = sources.source(setup)
        sources.run(setup.token, source.id)
        def before = sources.documents(setup.token, source.id)
        models.embeddings.clear()

        when: "a changed, b only touched (same content, a new time), c gone"
        sources.write("${base}/a.txt", "Zweite Fassung. " + TEXT * 5)
        fake.modified["${base}/b.txt".toString()] = Instant.now().plusSeconds(60)
        fake.files.remove("${base}/c.txt".toString())
        def done = sources.run(setup.token, source.id)
        def after = sources.documents(setup.token, source.id)

        then: "a replaced in place, b read but not embedded, c deleted"
        done.lastChanged == 1
        done.lastAdded == 0
        done.lastDeleted == 1
        after.keySet() == ["a.txt", "b.txt"] as Set
        after["a.txt"].id == before["a.txt"].id
        models.embeddings.size() == 1
        models.embeddings[0].texts.any { it.contains("Zweite Fassung") }

        when: "nothing changed"
        models.embeddings.clear()
        def again = sources.run(setup.token, source.id)

        then: "nothing is read or embedded"
        again.lastAdded == 0
        again.lastChanged == 0
        models.embeddings.isEmpty()
    }

    def "a refused file is remembered and not read again until it changes"() {
        given:
        def setup = sources.tenant()
        String base = setup.base
        fake.files["${base}/bild.txt".toString()] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0] as byte[]
        fake.modified["${base}/bild.txt".toString()] = Instant.now()
        sources.write("${base}/leer.txt", "")
        def source = sources.source(setup)

        when:
        def first = sources.run(setup.token, source.id)

        then: "both refused with their reason"
        first.lastRefused == 2
        (api.call(setup.token, "GET", "${SOURCES}/${source.id}/files") as List<Map>)
                .collectEntries { [(it.path): it.reason] } == ["bild.txt": "document.imageNeedsScanModel", "leer.txt": "document.empty"]

        when: "run again, unchanged"
        def second = sources.run(setup.token, source.id)

        then: "not read again"
        second.lastRefused == 0
        second.openFiles == 0

        when: "the empty one gets text"
        sources.write("${base}/leer.txt", "Jetzt mit Text. " + TEXT * 5)
        def third = sources.run(setup.token, source.id)

        then:
        third.lastAdded == 1
        (api.call(setup.token, "GET", "${SOURCES}/${source.id}/files") as List<Map>)*.path == ["bild.txt"]
    }

    def "a file the store cannot read is left open and the others are read - the error is shown with the source"() {
        given: "three files, the store refuses to read the first by path"
        def setup = sources.tenant()
        String base = setup.base
        sources.write("${base}/a-gesperrt.txt", TEXT)
        sources.write("${base}/b-lesbar.txt", TEXT * 2)
        sources.write("${base}/c-lesbar.txt", TEXT * 3)
        fake.unreadable.add("${base}/a-gesperrt.txt".toString())
        def source = sources.source(setup)

        when:
        def first = sources.run(setup.token, source.id)

        then: "the two readable files are in the knowledge, the locked one stays open, the error is the store's"
        first.lastAdded == 2
        first.lastRefused == 0
        first.openFiles == 1
        first.lastError == "fileStore.readFailed"
        sources.documents(setup.token, source.id).keySet() == ["b-lesbar.txt", "c-lesbar.txt"] as Set

        when: "the store lets it be read, and a readable one has vanished meanwhile"
        fake.unreadable.clear()
        fake.files.remove("${base}/c-lesbar.txt".toString())
        def second = sources.run(setup.token, source.id)

        then: "it is read now, the vanished one deleted - the listing was complete all along"
        second.lastAdded == 1
        second.lastDeleted == 1
        second.lastError == null
        sources.documents(setup.token, source.id).keySet() == ["a-gesperrt.txt", "b-lesbar.txt"] as Set
    }

    def "a scan refused without an image model is read once the tenant has one"() {
        given: "a PDF with a page of nothing but an image, and a tenant without an image model"
        def setup = sources.tenant()
        String base = setup.base
        fake.files["${base}/scan.pdf".toString()] = ScanReadingSpec.pdf([null])
        fake.modified["${base}/scan.pdf".toString()] = Instant.now()
        def source = sources.source(setup)

        when:
        def first = sources.run(setup.token, source.id)

        then:
        first.lastRefused == 1
        (api.call(setup.token, "GET", "${SOURCES}/${source.id}/files") as List<Map>)*.reason == ["document.noText"]

        when: "the tenant gets an image model - the file is unchanged"
        api.call(setup.token, "PUT", "/tenant/admin/provider-keys/GEMINI", [apiKey: "own-gemini-key-12"])
        api.allow(setup.tenant.id, api.chatModel("GEMINI", [readsScans: true]))
        def second = sources.run(setup.token, source.id)

        then: "read now"
        second.lastAdded == 1
        second.lastRefused == 0
        sources.documents(setup.token, source.id).keySet() == ["scan.pdf"] as Set
    }

    def "a file over 50 MB and a path over 500 characters are refused without reading them"() {
        given:
        def setup = sources.tenant()
        String base = setup.base
        fake.files["${base}/gross.txt".toString()] = new byte[50 * 1024 * 1024 + 1]
        fake.modified["${base}/gross.txt".toString()] = Instant.now()
        String longPath = (1..10).collect { "Ordner-mit-einem-sehr-langen-Namen-fuer-diesen-Test-" + it }.join("/") + "/datei.txt"
        sources.write("${base}/${longPath}", TEXT)
        def source = sources.source(setup)
        fake.hold = new CountDownLatch(1)

        when: "every read would wait - none comes"
        def done = sources.run(setup.token, source.id)

        then:
        done.lastRefused == 2
        (api.call(setup.token, "GET", "${SOURCES}/${source.id}/files") as List<Map>)
                .collectEntries { [(it.path): it.reason] } == ["gross.txt": "document.tooLarge", (longPath): "source.pathTooLong"]

        cleanup:
        fake.hold?.countDown()
    }

    def "a store out of reach, another fingerprint or a tree too deep delete nothing - the error is shown with the source"() {
        given:
        def setup = sources.tenant()
        String base = setup.base
        sources.write("${base}/bleibt.txt", TEXT * 5)
        def source = sources.source(setup)
        def read = sources.run(setup.token, source.id)

        when: "the file vanished, but the store is out of reach"
        fake.files.remove("${base}/bleibt.txt".toString())
        fake.unreachable = true
        def failed = sources.run(setup.token, source.id)

        then: "nothing deleted, the last success kept"
        failed.lastError == "fileStore.unreachable"
        failed.lastSucceededAt == read.lastSucceededAt
        sources.documents(setup.token, source.id).keySet() == ["bleibt.txt"] as Set

        when: "the server presents another key"
        fake.unreachable = false
        fake.presented = "SHA256:" + "A" * 43

        then:
        sources.run(setup.token, source.id).lastError == "fileStore.fingerprintChanged"
        sources.documents(setup.token, source.id).keySet() == ["bleibt.txt"] as Set

        when: "reachable again, but a folder lies deeper than the listing walks"
        fake.presented = FakeStoreClient.FINGERPRINT
        sources.write("${base}/" + (1..20).collect { "e" + it }.join("/") + "/tief.txt", TEXT)
        def deep = sources.run(setup.token, source.id)

        then: "the listing is incomplete: still nothing deleted"
        deep.lastError == "source.tooManyFiles"
        sources.documents(setup.token, source.id).keySet() == ["bleibt.txt"] as Set
    }

    def "folders and names the pattern passes over count towards what a listing walks - beyond it nothing is deleted"() {
        given:
        def setup = sources.tenant()
        String base = setup.base
        sources.write("${base}/bleibt.txt", TEXT * 5)
        def source = sources.source(setup, [pattern: "*.txt"])
        sources.run(setup.token, source.id)

        when: "the file vanished, and the folder holds more other names than a listing walks"
        fake.files.remove("${base}/bleibt.txt".toString())
        (1..(SourceSync.MAX_LISTED + 1)).each { fake.files.put("${base}/anderes-${it}.log".toString(), "x".bytes) }
        def walked = sources.run(setup.token, source.id)

        then: "the listing is incomplete: nothing deleted"
        walked.lastError == "source.tooManyFiles"
        sources.documents(setup.token, source.id).keySet() == ["bleibt.txt"] as Set
    }

    def "a document deleted by hand stays away until its file changes; one moved by hand stays where it was moved"() {
        given:
        def setup = sources.tenant()
        String base = setup.base
        sources.write("${base}/weg.txt", "Weg. " + TEXT * 5)
        sources.write("${base}/bewegt.txt", "Bewegt. " + TEXT * 5)
        def area = api.call(setup.token, "POST", "/tenant/admin/knowledge-areas", [name: "Werkstatt"]) as Map
        def source = sources.source(setup)
        sources.run(setup.token, source.id)
        def documents = sources.documents(setup.token, source.id)

        when: "one deleted, one moved into an area"
        api.status(setup.token, "DELETE", "/tenant/admin/documents/${documents["weg.txt"].id}") == HttpStatus.NO_CONTENT
        api.call(setup.token, "PUT", "/tenant/admin/documents/${documents["bewegt.txt"].id}/area", [areaId: area.id])
        def unchanged = sources.run(setup.token, source.id)

        then: "the deleted one is not read again, and shown as deleted by hand"
        unchanged.lastAdded == 0
        sources.documents(setup.token, source.id).keySet() == ["bewegt.txt"] as Set
        (api.call(setup.token, "GET", "${SOURCES}/${source.id}/files") as List<Map>)
                .collect { [it.path, it.reason] } == [["weg.txt", null]]

        when: "both files change"
        sources.write("${base}/weg.txt", "Weg, neu. " + TEXT * 5)
        sources.write("${base}/bewegt.txt", "Bewegt, neu. " + TEXT * 5)
        def changed = sources.run(setup.token, source.id)
        def after = sources.documents(setup.token, source.id)

        then: "the deleted one is back as a new document, the moved one replaced in its area"
        changed.lastAdded == 1
        changed.lastChanged == 1
        after["weg.txt"].id != documents["weg.txt"].id
        after["bewegt.txt"].id == documents["bewegt.txt"].id
        after["bewegt.txt"].areaId == area.id
    }

    def "a document deleted by the client software counts as deleted by hand"() {
        given:
        def setup = sources.tenant()
        sources.write("${setup.base}/api.txt", "Per API. " + TEXT * 5)
        def source = sources.source(setup)
        sources.run(setup.token, source.id)
        def document = sources.documents(setup.token, source.id)["api.txt"]
        def key = (api.call(setup.token, "POST", "/tenant/admin/api-keys", [name: "Fachsoftware"]) as Map).plainKey

        when:
        def deleted = api.status(key, "DELETE", "/api/v1/documents/${document.id}")
        def done = sources.run(setup.token, source.id)

        then:
        deleted == HttpStatus.NO_CONTENT
        done.lastAdded == 0
        (api.call(setup.token, "GET", "${SOURCES}/${source.id}/files") as List<Map>)*.reason == [null]
    }

    def "another area moves the documents still in the old one; another folder reads anew"() {
        given:
        def setup = sources.tenant()
        String base = setup.base
        sources.write("${base}/eins/a.txt", "Eins. " + TEXT * 5)
        sources.write("${base}/eins/b.txt", "Zwei. " + TEXT * 5)
        sources.write("${base}/zwei/c.txt", "Drei. " + TEXT * 5)
        def sales = api.call(setup.token, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map
        def workshop = api.call(setup.token, "POST", "/tenant/admin/knowledge-areas", [name: "Werkstatt"]) as Map
        def source = sources.source(setup, [folder: "eins", areaId: sales.id])
        sources.run(setup.token, source.id)
        def documents = sources.documents(setup.token, source.id)
        api.call(setup.token, "PUT", "/tenant/admin/documents/${documents["b.txt"].id}/area", [areaId: null])
        models.embeddings.clear()

        when: "the source writes into another area"
        api.call(setup.token, "PUT", "${SOURCES}/${source.id}", [name: source.name, fileStoreId: setup.store.id,
                folder: "eins", areaId: workshop.id])
        def moved = sources.documents(setup.token, source.id)

        then: "a moves along, b - moved by hand - stays; nothing embedded again"
        moved["a.txt"].areaId == workshop.id
        moved["b.txt"].areaId == null
        models.embeddings.isEmpty()

        when: "the source reads another folder"
        api.call(setup.token, "PUT", "${SOURCES}/${source.id}", [name: source.name, fileStoreId: setup.store.id,
                folder: "/zwei/", areaId: workshop.id])
        def listed = sources.await(setup.token, source.id)

        then: "its documents are gone, the new folder listed"
        listed.folder == "zwei"
        listed.openFiles == 1
        sources.documents(setup.token, source.id).isEmpty()
        sources.run(setup.token, source.id).lastAdded == 1
        sources.documents(setup.token, source.id).keySet() == ["c.txt"] as Set
    }

    def "a narrower pattern deletes what no longer matches"() {
        given:
        def setup = sources.tenant()
        sources.write("${setup.base}/a.txt", "A. " + TEXT * 5)
        sources.write("${setup.base}/b.md", "B. " + TEXT * 5)
        def source = sources.source(setup)
        sources.run(setup.token, source.id)

        when:
        api.call(setup.token, "PUT", "${SOURCES}/${source.id}", [name: source.name, fileStoreId: setup.store.id,
                pattern: "*.md"])
        sources.await(setup.token, source.id)
        def done = sources.run(setup.token, source.id)

        then:
        done.lastDeleted == 1
        sources.documents(setup.token, source.id).keySet() == ["b.md"] as Set
    }

    def "a source is removed with its documents - or keeps them as ordinary ones"() {
        given:
        def setup = sources.tenant()
        sources.write("${setup.base}/a.txt", "A. " + TEXT * 5)
        def gone = sources.source(setup)
        sources.run(setup.token, gone.id)
        def goneDocument = sources.documents(setup.token, gone.id)["a.txt"]
        def kept = sources.source(setup)
        sources.run(setup.token, kept.id)
        def keptDocument = sources.documents(setup.token, kept.id)["a.txt"]

        when:
        api.status(setup.token, "DELETE", "${SOURCES}/${gone.id}")
        api.status(setup.token, "DELETE", "${SOURCES}/${kept.id}?keepDocuments=true")
        def documents = api.call(setup.token, "GET", "/tenant/admin/documents") as List<Map>

        then: "the first one's document is gone, the second one's an upload of no source"
        documents.find { it.id == goneDocument.id } == null
        with(documents.find { it.id == keptDocument.id }) {
            origin == "UPLOAD"
            sourceId == null
            status == "READY"
        }
        (api.call(setup.token, "GET", SOURCES) as List).isEmpty()
    }

    def "no run starts while a source is removed - neither the scheduler's nor one by hand"() {
        given:
        def setup = sources.tenant()
        def source = sources.source(setup)
        Long sourceId = source.id as Long
        Long tenantId = setup.tenant.id as Long
        def startedMeanwhile = null
        def byHandMeanwhile = null

        when: "the removal holds the source as KnowledgeSourceService.delete does"
        def held = sync.holding(sourceId) {
            startedMeanwhile = sync.start(tenantId, sourceId, SourceSync.Kind.SCHEDULED)
            byHandMeanwhile = sources.refusal(setup.token, "POST", "${SOURCES}/${sourceId}/run")
        }

        then:
        held
        startedMeanwhile == false
        byHandMeanwhile == [409, "source.running"]

        and: "free again afterwards"
        api.status(setup.token, "DELETE", "${SOURCES}/${sourceId}") == HttpStatus.NO_CONTENT
        (api.call(setup.token, "GET", SOURCES) as List).isEmpty()
    }

    def "a running source is neither removed nor pointed elsewhere; a cancel stops it after the file it reads"() {
        given:
        def setup = sources.tenant()
        (1..5).each { sources.write("${setup.base}/datei-${it}.txt", "Datei ${it}. " + TEXT * 5) }
        def source = sources.source(setup)
        fake.hold = new CountDownLatch(1)

        when:
        api.call(setup.token, "POST", "${SOURCES}/${source.id}/run")

        then: "while it runs"
        sources.refusal(setup.token, "POST", "${SOURCES}/${source.id}/run") == [409, "source.running"]
        sources.refusal(setup.token, "DELETE", "${SOURCES}/${source.id}") == [409, "source.running"]
        sources.refusal(setup.token, "PUT", "${SOURCES}/${source.id}", [name: source.name,
                fileStoreId: setup.store.id, folder: "anders"]) == [409, "source.running"]
        api.status(setup.token, "PUT", "${SOURCES}/${source.id}", [name: "Umbenannt", fileStoreId: setup.store.id]) == HttpStatus.OK

        when: "cancelled while it reads its first file"
        awaitReading()
        api.status(setup.token, "POST", "${SOURCES}/${source.id}/cancel")
        fake.hold.countDown()
        def done = sources.await(setup.token, source.id)

        then: "what was read stays, the rest is open"
        done.lastError == "source.cancelled"
        done.lastAdded == 1
        done.openFiles == 4
    }

    /** Until the run reads its first file - the fake holds it there. */
    private void awaitReading() {
        long deadline = System.currentTimeMillis() + 10_000
        while (fake.reads.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    def "a tenant without an embedding model, or a store not confirmed: nothing is read"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)
        String base = "/ablage-" + AiApi.unique()
        fake.folders.add(base)
        sources.write("${base}/a.txt", TEXT * 5)
        def store = sources.store(token, base)
        def unconfirmed = api.call(token, "POST", "/tenant/admin/file-stores", [name: "Neu", type: "SFTP",
                host: "localhost", username: "check", password: "check-secret", basePath: base]) as Map

        when:
        def created = api.call(token, "POST", SOURCES, [name: "Ohne Modell", fileStoreId: store.id]) as Map
        def source = sources.await(token, created.id)

        then:
        source.lastError == "document.chooseEmbeddingModel"
        source.openFiles == 0
        sources.refusal(token, "POST", SOURCES, [name: "Unbestätigt", fileStoreId: unconfirmed.id]) ==
                [409, "source.storeNotReady"]
    }

    def "every rule of a source's settings: #code"() {
        given:
        def setup = sources.tenant()
        def otherStore = sources.tenant().store

        expect:
        sources.refusal(setup.token, "POST", SOURCES, [name: "Quelle", fileStoreId: setup.store.id] + body(otherStore)) ==
                [400, code]

        where:
        body                                                           || code
        ({ s -> [name: " "] })                                         || "source.nameRequired"
        ({ s -> [name: "x" * 81] })                                    || "source.nameTooLong"
        ({ s -> [fileStoreId: null] })                                 || "source.storeNotFound"
        ({ s -> [fileStoreId: s.id] })                                 || "source.storeNotFound"
        ({ s -> [folder: "../oben"] })                                 || "source.folderInvalid"
        ({ s -> [folder: "a/./b"] })                                   || "source.folderInvalid"
        ({ s -> [folder: "a\\b"] })                                    || "source.folderInvalid"
        ({ s -> [areaId: 999_999_999] })                               || "knowledgeArea.unknown"
        ({ s -> [pattern: "ordner/*.pdf"] })                           || "source.patternInvalid"
        ({ s -> [pattern: "x" * 201] })                                || "source.patternInvalid"
        ({ s -> [schedule: "MINUTELY"] })                              || "source.scheduleUnknown"
    }

    def "names are unique per tenant; the defaults; a schedule's next time, none by hand or switched off"() {
        given:
        def setup = sources.tenant()

        when:
        def daily = sources.source(setup, [name: "Täglich", folder: " /Projekte// "])
        def manual = sources.source(setup, [name: "Von Hand", schedule: "manual"])
        def paused = sources.source(setup, [name: "Pausiert", schedule: "HOURLY", active: false])

        then:
        sources.refusal(setup.token, "POST", SOURCES, [name: "täglich", fileStoreId: setup.store.id]) ==
                [409, "source.nameTaken"]
        daily.schedule == "DAILY"
        daily.folder == "Projekte"
        daily.active
        daily.nextRunAt != null
        manual.schedule == "MANUAL"
        manual.nextRunAt == null
        paused.nextRunAt == null

        when: "switched on"
        def on = api.call(setup.token, "PUT", "${SOURCES}/${paused.id}", [name: "Pausiert", fileStoreId: setup.store.id,
                schedule: "HOURLY", active: true]) as Map

        then:
        on.nextRunAt != null
    }

    def "a source switched off runs by hand"() {
        given:
        def setup = sources.tenant()
        sources.write("${setup.base}/a.txt", TEXT * 5)
        def paused = sources.source(setup, [active: false])

        expect:
        sources.run(setup.token, paused.id).lastAdded == 1
    }

    def "another tenant's source, store and area are not found - and a store or an area a source uses is not deleted"() {
        given:
        def setup = sources.tenant()
        def other = sources.tenant()
        def area = api.call(setup.token, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map
        def source = sources.source(setup, [areaId: area.id])

        expect: "the other tenant does not reach it"
        api.status(other.token, "PUT", "${SOURCES}/${source.id}", [name: "x", fileStoreId: other.store.id]) == HttpStatus.NOT_FOUND
        api.status(other.token, "DELETE", "${SOURCES}/${source.id}") == HttpStatus.NOT_FOUND
        api.status(other.token, "POST", "${SOURCES}/${source.id}/run") == HttpStatus.NOT_FOUND
        api.status(other.token, "POST", "${SOURCES}/${source.id}/cancel") == HttpStatus.NOT_FOUND
        api.status(other.token, "GET", "${SOURCES}/${source.id}/files") == HttpStatus.NOT_FOUND
        (api.call(other.token, "GET", SOURCES) as List).isEmpty()

        and: "the store and the area it uses stay"
        sources.refusal(setup.token, "DELETE", "/tenant/admin/file-stores/${setup.store.id}") == [409, "fileStore.usedBySource"]
        sources.refusal(setup.token, "DELETE", "/tenant/admin/knowledge-areas/${area.id}") == [409, "knowledgeArea.usedBySource"]

        and: "the store keeps its folder - its next listing would delete the source's documents - but may be renamed"
        def store = [type: "SFTP", host: "localhost", port: 22, username: "check"]
        sources.refusal(setup.token, "PUT", "/tenant/admin/file-stores/${setup.store.id}",
                store + [name: setup.store.name, basePath: setup.base + "/anderswo"]) == [409, "fileStore.usedBySource"]
        api.status(setup.token, "PUT", "/tenant/admin/file-stores/${setup.store.id}",
                store + [name: "Umbenannt", basePath: setup.base]) == HttpStatus.OK
    }

    def "a tenant with a source is deleted together with its store, area and documents"() {
        given: "the source reads the store into the area - the references have no ON DELETE"
        def setup = sources.tenant()
        sources.write("${setup.base}/a.txt", TEXT * 5)
        def area = api.call(setup.token, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map
        def source = sources.source(setup, [areaId: area.id])
        sources.run(setup.token, source.id)

        when:
        def status = api.status(api.adminToken, "DELETE", "/admin/tenants/${setup.tenant.id}")

        then:
        status == HttpStatus.NO_CONTENT
        api.status(api.adminToken, "GET", "/admin/tenants/${setup.tenant.id}") == HttpStatus.NOT_FOUND
    }

    def "the admin keeps a tenant's sources like its tenant admins"() {
        given:
        def setup = sources.tenant()
        sources.write("${setup.base}/a.txt", TEXT * 5)
        String base = "/admin/tenants/${setup.tenant.id}/knowledge-sources"

        when:
        def created = api.admin("POST", base, [name: "Vom Admin", fileStoreId: setup.store.id]) as Map
        def listed = sources.await(setup.token, created.id)
        api.admin("POST", "${base}/${created.id}/run")
        def done = sources.await(setup.token, created.id)

        then:
        listed.openFiles == 1
        done.lastAdded == 1
        (api.admin("GET", base) as List<Map>)*.name == ["Vom Admin"]
        api.status(api.adminToken, "DELETE", "${base}/${created.id}") == HttpStatus.NO_CONTENT
    }
}
