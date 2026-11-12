package de.alpdesk.services.task

import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.repositories.task.TaskRunRepository
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.mcp.FakeMcpSoftware
import de.alpdesk.services.tenant.TenantDatabases
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.mail.Multipart
import spock.lang.Shared
import spock.lang.Specification

import javax.imageio.ImageIO
import de.alpdesk.entities.oauth.ClientIdSource
import de.alpdesk.entities.oauth.GrantStatus
import de.alpdesk.entities.oauth.OAuthGrant
import de.alpdesk.repositories.oauth.OAuthGrantRepository

import javax.sql.DataSource
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch

import static de.alpdesk.services.task.TaskApi.LOGS

/**
 * Documents in a task (docs/architecture/tasks.md "read_documents"): whole documents of a store's folder read once -
 * Tika's text, the image model's reading of a scan -, each a run file; one that cannot be read is kept with its reason,
 * never a failed run. The store is FakeStoreClient, the models FakeAiModels.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
class TaskDocumentsSpec extends Specification {

    static final String INBOX = LOGS + "/inbox"

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels models

    @Inject
    FakeStoreClient store

    @Inject
    TaskRunRepository runs

    @Inject
    TenantRepository tenants

    @Inject
    TenantDatabases databases

    @Inject
    OAuthGrantRepository grants

    @Shared
    AiApi api

    @Shared
    TaskApi tasks

    @Shared
    FakeMcpSoftware software = new FakeMcpSoftware()

    def cleanupSpec() {
        software.stop()
    }

    def setup() {
        api = api ?: new AiApi(client)
        tasks = tasks ?: new TaskApi(api)
        TaskApi.mailbox()
        models.reset()
        store.reset()
        software.received.clear()
        software.refusedSources.clear()
        software.answers.clear()
        software.key = null
    }

    def "the documents whose names match a pattern are read, ignoring case - the subfolders too; each one's text is a run file"() {
        given:
        def setup = tasks.tenant()
        Instant now = Instant.now()
        store.put("${INBOX}/a.txt", "Rechnung RE-1 der Musterfirma über 119,00 EUR".bytes, now.minusSeconds(30))
        store.put("${INBOX}/2026/03/B.MD", "# Gutschrift\nBetrag: 20,00 EUR".bytes, now.minusSeconds(20))
        store.put("${INBOX}/notes.xyz", "keine Rechnung".bytes, now.minusSeconds(10))
        store.put("${INBOX}/.hidden.txt", "versteckt".bytes, now)
        def task = tasks.create(setup.token, documents([folder: "inbox", subfolders: true, pattern: '["*.txt", "*.md"]']))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def result = (run.steps as List<Map>)[0].result as Map
        result.files == 2
        (result.documents as List<Map>)*.name == ["a.txt", "2026/03/B.MD"]
        (result.documents as List<Map>).every { (it.source as String) ==~ /${it.name}#[0-9a-f]{12}/ && it.problem == null }
        def files = runs.findFiles(dataSource(setup), (run.run as Map).id as Long, (result.fileIds as List)*.longValue())
        files*.name == ["a.txt", "2026/03/B.MD"]
        files[0].lines()*.text() == ["Rechnung RE-1 der Musterfirma über 119,00 EUR"]
        files[1].lines()*.text().contains("Betrag: 20,00 EUR")
    }

    def "without subfolders only the folder itself is read"() {
        given:
        def setup = tasks.tenant()
        store.put("${INBOX}/a.txt", "oben".bytes)
        store.put("${INBOX}/2026/b.txt", "darunter".bytes)
        def task = tasks.create(setup.token, documents([folder: "inbox", pattern: "'*.txt'"]))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (((run.steps as List<Map>)[0].result as Map).documents as List<Map>)*.name == ["a.txt"]
    }

    def "a document is read once: unchanged it is skipped, changed it is read again with another source"() {
        given:
        def setup = tasks.tenant()
        store.put("${INBOX}/a.txt", "erste Fassung".bytes, Instant.parse("2026-03-01T10:00:00.123456Z"))
        def task = tasks.create(setup.token, documents([folder: "inbox", pattern: "'*'"]))
        def first = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        when:
        def second = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "a time finer than milliseconds does not count it as changed"
        ((second.steps as List<Map>)[0].result as Map).files == 0

        when:
        store.put("${INBOX}/a.txt", "zweite Fassung".bytes, Instant.parse("2026-03-02T10:00:00Z"))
        def third = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def before = (((first.steps as List<Map>)[0].result as Map).documents as List<Map>)[0]
        def after = (((third.steps as List<Map>)[0].result as Map).documents as List<Map>)[0]
        after.name == "a.txt"
        after.source != before.source
    }

    def "a broken document, an empty one and a scan without an image model are kept with their reason - the run does not fail"() {
        given:
        def setup = tasks.tenant()
        store.put("${INBOX}/kaputt.pdf", ([0x00, 0x01, 0x02, 0xFF, 0xFE] * 200) as byte[])
        store.put("${INBOX}/leer.txt", new byte[0])
        store.put("${INBOX}/foto.png", png())
        def task = tasks.create(setup.token, documents([folder: "inbox", pattern: "'*'"]))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def documents = ((run.steps as List<Map>)[0].result as Map).documents as List<Map>
        documents.size() == 3
        documents.every { it.problem != null && it.fileId == null }
        documents.find { it.name == "foto.png" }.problem.contains("No model reads images")
        documents.find { it.name == "kaputt.pdf" }.problem == "The document could not be read"
        documents.find { it.name == "leer.txt" }.problem == "The document is empty"

        and: "the scan waiting for a model has no checksum in its source - its later clear row is not taken for it"
        documents.find { it.name == "foto.png" }.source == "foto.png"
        documents.find { it.name == "kaputt.pdf" }.source.startsWith("kaputt.pdf#")

        when: "the broken and the empty one are not read again; the scan is - an image model may come"
        def second = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (((second.steps as List<Map>)[0].result as Map).documents as List<Map>)*.name == ["foto.png"]

        when: "the admin assigns an image model - the scan is read"
        def scanner = api.chatModel("GEMINI", [readsScans: true])
        api.allow(setup.tenant.id, scanner)
        def third = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def scan = (((third.steps as List<Map>)[0].result as Map).documents as List<Map>)[0]
        scan.name == "foto.png"
        scan.problem == null
        scan.fileId != null
        scan.source.startsWith("foto.png#")
    }

    def "an image the image model is never shown - broken, or not an image after all - is reported once; the run does not fail"() {
        given: "the tenant has an image model, and a PNG whose header promises an image its data does not hold"
        def setup = tasks.tenant()
        def scanner = api.chatModel("GEMINI", [readsScans: true])
        api.allow(setup.tenant.id, scanner)
        byte[] broken = ([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] + [0x00, 0x13, 0x37, 0xFF] * 100) as byte[]
        store.put("${INBOX}/kaputt.png", broken)
        store.put("${INBOX}/beleg.txt", "Beleg A".bytes)
        def task = tasks.create(setup.token, documents([folder: "inbox", pattern: "'*'"]))

        when:
        def first = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        def second = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "the other document is read, the image reported with its reason"
        (first.run as Map).state == "DONE"
        def documents = ((first.steps as List<Map>)[0].result as Map).documents as List<Map>
        documents.find { it.name == "kaputt.png" }.problem == "The image could not be read"
        documents.find { it.name == "beleg.txt" }.fileId != null

        and: "and not read again - the run after it has nothing left"
        (second.run as Map).state == "DONE"
        (((second.steps as List<Map>)[0].result as Map).documents as List<Map>).isEmpty()
    }

    def "a document the store refuses to read is reported and read again by the next run - the others are read"() {
        given:
        def setup = tasks.tenant()
        store.put("${INBOX}/gesperrt.txt", "Beleg A".bytes)
        store.put("${INBOX}/frei.txt", "Beleg B".bytes)
        store.unreadable.add("${INBOX}/gesperrt.txt".toString())
        def task = tasks.create(setup.token, documents([folder: "inbox", pattern: "'*.txt'"]))

        when:
        def first = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        store.unreadable.clear()
        def second = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (first.run as Map).state == "DONE"
        def documents = ((first.steps as List<Map>)[0].result as Map).documents as List<Map>
        documents.find { it.name == "gesperrt.txt" }.problem.contains("failed")
        documents.find { it.name == "frei.txt" }.fileId != null
        (((second.steps as List<Map>)[0].result as Map).documents as List<Map>)*.name == ["gesperrt.txt"]
    }

    def "a provider failing while it reads a scan fails the run - the scan is read again next time, not reported as unreadable"() {
        given:
        def setup = tasks.tenant()
        def scanner = api.chatModel("GEMINI", [readsScans: true])
        api.allow(setup.tenant.id, scanner)
        store.put("${INBOX}/beleg.png", png())
        def task = tasks.create(setup.token, documents([folder: "inbox", pattern: "'*.png'"]))
        models.failures = 1

        when:
        def failed = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        def next = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (failed.run as Map).state == "FAILED"
        (failed.run as Map).errorCode == "provider.keyRefused"
        (((next.steps as List<Map>)[0].result as Map).documents as List<Map>)[0].fileId != null
    }

    def "a scan is read by the tenant's image model - its call counted into the step's cost"() {
        given:
        def setup = tasks.tenant()
        def scanner = api.chatModel("GEMINI", [readsScans: true])
        api.allow(setup.tenant.id, scanner)
        store.put("${INBOX}/beleg.png", png())
        def task = tasks.create(setup.token, documents([folder: "inbox", pattern: "'*.png'"]))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def step = (run.steps as List<Map>)[0]
        def files = runs.findFiles(dataSource(setup), (run.run as Map).id as Long, ((step.result as Map).fileIds as List)*.longValue())
        files[0].lines()*.text().join("\n").contains("Gelesen vom Bildmodell")
        step.inputTokens > 0
        (step.cost as BigDecimal) > 0
    }

    def "at most 50 documents a run, the oldest first - the rest come with the next run"() {
        given:
        def setup = tasks.tenant()
        Instant start = Instant.parse("2026-01-01T00:00:00Z")
        (1..52).each { store.put("${INBOX}/beleg-${it}.txt", "Beleg ${it}".bytes, start.plusSeconds(it)) }
        def task = tasks.create(setup.token, documents([folder: "inbox", pattern: "'*.txt'"]))

        when:
        def first = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        def second = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def firstResult = (first.steps as List<Map>)[0].result as Map
        firstResult.files == 50
        firstResult.truncated == true
        !((firstResult.documents as List<Map>)*.name).contains("beleg-51.txt")
        def secondResult = (second.steps as List<Map>)[0].result as Map
        (secondResult.documents as List<Map>)*.name == ["beleg-51.txt", "beleg-52.txt"]
        secondResult.truncated == false
    }

    def "documents to be read again do not count to the 50 - 51 scans waiting for an image model hold back no newer one"() {
        given:
        def setup = tasks.tenant()
        Instant start = Instant.parse("2026-01-01T00:00:00Z")
        (1..51).each { store.put("${INBOX}/scan-${it}.png", png(), start.plusSeconds(it)) }
        store.put("${INBOX}/beleg.txt", "Beleg".bytes, start.plusSeconds(100))
        def task = tasks.create(setup.token, documents([folder: "inbox", pattern: "'*'"]))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def result = (run.steps as List<Map>)[0].result as Map
        def documents = result.documents as List<Map>
        documents.size() == 52
        documents.find { it.name == "beleg.txt" }.fileId != null
        result.truncated == false
    }

    def "a store out of reach fails the run - the documents are read again by the next run"() {
        given:
        def setup = tasks.tenant()
        store.put("${INBOX}/a.txt", "Beleg".bytes)
        def task = tasks.create(setup.token, documents([folder: "inbox", pattern: "'*'"]))
        store.unreachable = true

        when:
        def failed = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        store.unreachable = false
        def next = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (failed.run as Map).state == "FAILED"
        (failed.run as Map).errorCode == "fileStore.unreachable"
        ((next.steps as List<Map>)[0].result as Map).files == 1
    }

    def "extract: every document gives a row of the fields, checked by their types - the run view, the client software and MCP see the rows, the mail tells the unclear ones"() {
        given:
        def setup = tasks.tenant()
        String mail = "buchhaltung-${UUID.randomUUID()}@example.com"
        store.put("${INBOX}/re-1.txt", "Rechnung\nkind: invoice\ndate: 2026-01-24\nnumber: RE-1\npartner: Musterfirma\ngross: 119.00\nnet: 100".bytes,
                Instant.parse("2026-01-24T08:00:00Z"))
        store.put("${INBOX}/leer.txt", new byte[0], Instant.parse("2026-01-25T08:00:00Z"))
        def task = tasks.create(setup.token, extracting(setup.model, mail))
        String apiKey = (api.call(setup.token, "POST", "/tenant/admin/api-keys", [name: "Software", taskIds: [task.id]]) as Map).plainKey
        String mcpKey = (api.call(setup.token, "POST", "/tenant/admin/mcp-keys", [name: "Claude", taskIds: [task.id]]) as Map).plainKey

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def result = (run.steps as List<Map>).find { it.stepId == "rows" }.result as Map
        result.rows == 2
        result.unclear == 1
        def rows = run.rows as List<Map>
        rows.size() == 2
        rows[0].stepId == "rows"
        (rows[0].source as String).startsWith("re-1.txt#")
        rows[0].values == [kind: "invoice", date: "2026-01-24", number: "RE-1", partner: "Musterfirma", net: 100, gross: 119]
        rows[0].unclear == null
        rows[1].source == "leer.txt#e3b0c44298fc"
        rows[1].values == [:]
        rows[1].unclear == "The document is empty"

        and: "the model was asked for the readable document only"
        models.chats.count { chat -> (chat.messages as List).any { it.toString().contains("You take values out of one document") } } == 1

        and: "the mail names the unclear row and sums the run up"
        String body = mailText(mail)
        body.contains("leer.txt#e3b0c44298fc: The document is empty")
        body.contains("2 document(s) read, 2 row(s), 1 of them unclear.")

        and: "the client software and the MCP server get the same rows"
        def viaApi = api.call(apiKey, "GET", "/api/v1/tasks/${task.id}/runs/${(run.run as Map).id}") as Map
        (viaApi.rows as List<Map>)*.source == rows*.source
        (viaApi.rows as List<Map>)[0].values.gross == 119
        String viaMcp = tool(mcpKey, "task_run", [task: task.name, run: (run.run as Map).id]).content[0].text
        viaMcp.contains("Rows taken out of documents (2):")
        viaMcp.contains("leer.txt#e3b0c44298fc: {} - unclear: The document is empty")
    }

    def "extract: an answer that is no result, or a value that is not its type, gets one more round - failing twice the row keeps what passed and is unclear"() {
        given:
        def setup = tasks.tenant()
        store.put("${INBOX}/a.txt", "[[row-broken]]\ndate: 2026-02-01\ngross: 10".bytes, Instant.parse("2026-02-01T00:00:00Z"))
        store.put("${INBOX}/b.txt", "[[row-wrong]]\ndate: 2026-02-02\ngross: 20".bytes, Instant.parse("2026-02-02T00:00:00Z"))
        store.put("${INBOX}/c.txt", "[[row-broken-twice]]\ndate: 2026-02-03".bytes, Instant.parse("2026-02-03T00:00:00Z"))
        store.put("${INBOX}/d.txt", "date: 2026-02-04\ngross: 12,50".bytes, Instant.parse("2026-02-04T00:00:00Z"))
        store.put("${INBOX}/e.txt", "date: 2026-02-05\ngross: 5\n[[unclear zwei Steuersätze]]".bytes, Instant.parse("2026-02-05T00:00:00Z"))
        def task = tasks.create(setup.token, extracting(setup.model, "it@example.com", """
              - {name: date, type: date, required: true}
              - {name: gross, type: amount, required: true}"""))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def rows = (run.rows as List<Map>).collectEntries { [(it.source.toString().takeWhile { c -> c != '#' }): it] }
        rows["a.txt"].values == [date: "2026-02-01", gross: 10]
        rows["a.txt"].unclear == null
        rows["b.txt"].values == [date: "2026-02-02", gross: 20]
        rows["b.txt"].unclear == null
        rows["c.txt"].values == [:]
        (rows["c.txt"].unclear as String).contains("gave no valid result")
        rows["d.txt"].values == [date: "2026-02-04"]
        rows["d.txt"].unclear == "Not taken from the document: gross (a number with . as the decimal separator)"
        rows["e.txt"].values == [date: "2026-02-05", gross: 5]
        rows["e.txt"].unclear == "zwei Steuersätze"
        ((run.steps as List<Map>).find { it.stepId == "rows" }.result as Map).unclear == 3
    }

    def "extract: the knowledge search is offered only with knowledge: true - the file tools always"() {
        given:
        def setup = tasks.tenant()
        store.put("${INBOX}/a.txt", "date: 2026-03-01".bytes)
        def embedding = api.embeddingModel()
        api.allow(setup.tenant.id, embedding)
        api.call(setup.token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        def plain = tasks.create(setup.token, extracting(setup.model, "it@example.com"))
        def withKnowledge = tasks.create(setup.token, extracting(setup.model, "it@example.com").replace(
                "    fields:", "    knowledge: true\n    fields:"))

        when:
        tasks.awaitRun(setup.token, plain.id, tasks.start(setup.token, plain.id))
        def without = models.chats.last().listedTools as List
        tasks.awaitRun(setup.token, withKnowledge.id, tasks.start(setup.token, withKnowledge.id))
        def with = models.chats.last().listedTools as List

        then:
        !without.contains("similarity_search")
        !without.contains("read_document")
        with.contains("similarity_search")
        with.contains("read_document")
    }

    def "read_documents: a cancel ends the run between documents - the documents after it are not read"() {
        given:
        def setup = tasks.tenant()
        (1..3).each { store.put("${INBOX}/beleg-${it}.txt", "date: 2026-04-0${it}".bytes, Instant.parse("2026-04-0${it}T00:00:00Z")) }
        def task = tasks.create(setup.token, extracting(setup.model, "it@example.com"))
        store.hold = new CountDownLatch(1)

        when:
        def runId = tasks.start(setup.token, task.id)
        long deadline = System.currentTimeMillis() + 20_000
        while (store.reads.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        api.status(setup.token, "POST", "${TaskApi.TASKS}/${task.id}/runs/${runId}/cancel")
        store.hold.countDown()
        def run = tasks.awaitRun(setup.token, task.id, runId)

        then:
        (run.run as Map).state == "CANCELLED"
        store.reads.get() == 1
        models.chats.isEmpty()
    }

    def "extract: a cancel ends the run between documents - the documents after it are not asked about"() {
        given:
        def setup = tasks.tenant()
        (1..3).each { store.put("${INBOX}/beleg-${it}.txt", "date: 2026-04-0${it}".bytes, Instant.parse("2026-04-0${it}T00:00:00Z")) }
        def task = tasks.create(setup.token, extracting(setup.model, "it@example.com"))
        models.extractHold = new CountDownLatch(1)

        when:
        def runId = tasks.start(setup.token, task.id)
        long deadline = System.currentTimeMillis() + 20_000
        while (models.chats.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        api.status(setup.token, "POST", "${TaskApi.TASKS}/${task.id}/runs/${runId}/cancel")
        models.extractHold.countDown()
        def run = tasks.awaitRun(setup.token, task.id, runId)

        then:
        (run.run as Map).state == "CANCELLED"
        models.chats.size() == 1
    }

    def "extract: nothing read, no row - no model asked"() {
        given:
        def setup = tasks.tenant()
        store.folders.add(INBOX)
        def task = tasks.create(setup.token, extracting(setup.model, "it@example.com"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        ((run.steps as List<Map>).find { it.stepId == "rows" }.result as Map).rows == 0
        models.chats.isEmpty()
    }

    def "write_file: a document's name and a model's reason are guarded like a value - neither runs as a formula"() {
        given:
        def setup = tasks.tenant()
        tasks.allowWriting(setup)
        store.folders.add(LOGS + "/export/2026")
        store.put("${INBOX}/=summe.txt", "date: 2026-01-24\ngross: 119\npartner: Musterfirma\n[[unclear =SUMME(A1)]]".bytes,
                Instant.parse("2026-01-24T08:00:00Z"))
        def task = tasks.create(setup.token, writing(setup.model, "formel-${UUID.randomUUID()}@example.com"))

        when:
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        csv("export/2026/rows.csv").contains("24.01.2026;119,00;Musterfirma;'=SUMME(A1);'=summe.txt#")
    }

    def "write_file: the rows go into the CSV of their own year - the next run writes nothing twice, a changed document gives a row more"() {
        given:
        def setup = tasks.tenant()
        tasks.allowWriting(setup)
        store.folders.addAll([LOGS + "/export/2025", LOGS + "/export/2026"])
        String mail = "csv-${UUID.randomUUID()}@example.com"
        store.put("${INBOX}/re-1.txt", "date: 2026-01-24\ngross: 119\npartner: Musterfirma".bytes, Instant.parse("2026-01-24T08:00:00Z"))
        store.put("${INBOX}/re-0.txt", "date: 2025-12-14\ngross: 20.5\npartner: =HYPERLINK(1)".bytes, Instant.parse("2026-01-25T08:00:00Z"))
        def task = tasks.create(setup.token, writing(setup.model, mail))

        when:
        def first = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (first.run as Map).state == "DONE"
        def result = (first.steps as List<Map>).find { it.stepId == "csv" }.result as Map
        result.rows == 2
        result.skipped == 0
        result.written == ["export/2025/rows.csv", "export/2026/rows.csv"]
        def year2026 = csv("export/2026/rows.csv")
        year2026.startsWith("﻿date;gross;partner;unclear;source\r\n")
        year2026.contains("24.01.2026;119,00;Musterfirma;;re-1.txt#")
        csv("export/2025/rows.csv").contains("14.12.2025;20,50;'=HYPERLINK(1);;re-0.txt#")
        mailText(mail).contains("2 row(s) written into export/2025/rows.csv, export/2026/rows.csv, 0 already there.")

        when: "nothing new: nothing is written"
        int writes = store.written.get()
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        store.written.get() == writes

        when: "a document changed: one row more, the old one stays"
        store.put("${INBOX}/re-1.txt", "date: 2026-01-24\ngross: 120\npartner: Musterfirma".bytes, Instant.parse("2026-02-01T08:00:00Z"))
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def lines = csv("export/2026/rows.csv").split("\r\n")
        lines.size() == 3
        lines[1].contains(";119,00;")
        lines[2].contains(";120,00;")
    }

    def "write_file: a run that wrote and then failed is read again - the rows already in the file are skipped, nothing twice"() {
        given:
        def setup = tasks.tenant()
        tasks.allowWriting(setup)
        store.folders.add(LOGS + "/export/2026")
        store.put("${INBOX}/a.txt", "date: 2026-03-01\ngross: 1".bytes)
        store.put("${INBOX}/b.txt", "date: 2026-03-02\ngross: 2".bytes)
        def task = tasks.create(setup.token, writing(setup.model, "it@example.com"))
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        String before = csv("export/2026/rows.csv")

        when: "the read positions are lost - as when a step after write_file failed"
        dataSource(setup).connection.withCloseable { it.createStatement().execute("DELETE FROM task_read_position") }
        def again = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def result = (again.steps as List<Map>).find { it.stepId == "csv" }.result as Map
        result.rows == 0
        result.skipped == 2
        csv("export/2026/rows.csv") == before
    }

    def "write_file: fields added at the end are taken, the old rows get empty values - other changed columns are refused"() {
        given:
        def setup = tasks.tenant()
        tasks.allowWriting(setup)
        store.folders.add(LOGS + "/export/2026")
        store.put("${INBOX}/a.txt", "date: 2026-03-01\ngross: 1\npartner: Alt GmbH".bytes, Instant.parse("2026-03-01T00:00:00Z"))
        String source = writing(setup.model, "it@example.com")
        def task = tasks.create(setup.token, source)
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        when:
        api.call(setup.token, "PUT", "${TaskApi.TASKS}/${task.id}", [source: source.replace(
                "- {name: partner, type: text}", "- {name: partner, type: text}\n      - {name: number, type: text}")])
        store.put("${INBOX}/b.txt", "date: 2026-03-02\ngross: 2\npartner: Neu GmbH\nnumber: RE-7".bytes, Instant.parse("2026-03-02T00:00:00Z"))
        def added = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (added.run as Map).state == "DONE"
        def lines = csv("export/2026/rows.csv").substring(1).split("\r\n")
        lines[0] == "date;gross;partner;number;unclear;source"
        lines[1].startsWith("01.03.2026;1,00;Alt GmbH;;;a.txt#")
        lines[2].startsWith("02.03.2026;2,00;Neu GmbH;RE-7;;b.txt#")

        when: "a field renamed"
        api.call(setup.token, "PUT", "${TaskApi.TASKS}/${task.id}", [source: source.replace("name: partner", "name: lieferant")])
        store.put("${INBOX}/c.txt", "date: 2026-03-03\ngross: 3".bytes, Instant.parse("2026-03-03T00:00:00Z"))
        def refused = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (refused.run as Map).state == "FAILED"
        (refused.run as Map).errorCode == "task.fileHeaderChanged"
        csv("export/2026/rows.csv").substring(1).split("\r\n").size() == 3
    }

    def "write_file: a CSV a spreadsheet saved again as Windows-1252 is refused - its umlauts are not written back broken"() {
        given: "the file of the step, its old row with an umlaut, in Windows-1252"
        def setup = tasks.tenant()
        tasks.allowWriting(setup)
        store.folders.add(LOGS + "/export/2026")
        byte[] ansi = "date;gross;partner;unclear;source\r\n01.03.2026;1,00;Müller GmbH;;a.txt#0123456789ab\r\n"
                .getBytes("windows-1252")
        store.put("${LOGS}/export/2026/rows.csv", ansi)
        store.put("${INBOX}/b.txt", "date: 2026-03-02\ngross: 2\npartner: Neu GmbH".bytes, Instant.parse("2026-03-02T00:00:00Z"))
        def task = tasks.create(setup.token, writing(setup.model, "it@example.com"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "the run fails with its reason, the file stays as it was"
        (run.run as Map).state == "FAILED"
        (run.run as Map).errorCode == "task.fileNotUtf8"
        store.files[LOGS + "/export/2026/rows.csv"] == ansi
    }

    def "write_file: replace writes only this run's rows; a store whose switch was turned off fails the run"() {
        given:
        def setup = tasks.tenant()
        tasks.allowWriting(setup)
        store.folders.add(LOGS + "/export/2026")
        store.put("${INBOX}/a.txt", "date: 2026-03-01\ngross: 1".bytes, Instant.parse("2026-03-01T00:00:00Z"))
        def task = tasks.create(setup.token, writing(setup.model, "it@example.com").replace(
                "    file: 'export/{date:yyyy}/rows.csv'", "    file: 'export/{date:yyyy}/rows.csv'\n    mode: replace"))
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        when:
        store.put("${INBOX}/b.txt", "date: 2026-03-02\ngross: 2".bytes, Instant.parse("2026-03-02T00:00:00Z"))
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def lines = csv("export/2026/rows.csv").substring(1).split("\r\n")
        lines.size() == 2
        lines[1].startsWith("02.03.2026;2,00;")

        when:
        tasks.allowWriting(setup, false)
        store.put("${INBOX}/c.txt", "date: 2026-03-03\ngross: 3".bytes, Instant.parse("2026-03-03T00:00:00Z"))
        def refused = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (refused.run as Map).state == "FAILED"
        (refused.run as Map).errorCode == "task.storeNotWritable"
    }

    def "write_file: a folder that does not exist fails the run - the documents are read again next time"() {
        given:
        def setup = tasks.tenant()
        tasks.allowWriting(setup)
        store.put("${INBOX}/a.txt", "date: 2026-03-01\ngross: 1".bytes)
        def task = tasks.create(setup.token, writing(setup.model, "it@example.com"))

        when:
        def failed = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        store.folders.add(LOGS + "/export/2026")
        def next = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (failed.run as Map).state == "FAILED"
        (failed.run as Map).errorCode == "fileStore.folderNotFound"
        ((next.steps as List<Map>).find { it.stepId == "csv" }.result as Map).rows == 1
    }

    def "call_tool: every clear row goes to the tool with its source - unclear rows are held back; a refused call marks its row, the run ends unclear and the notify list hears of it"() {
        given:
        def setup = tasks.tenant()
        String mail = "buchhaltung-${UUID.randomUUID()}@example.com"
        def connection = booking(setup)
        store.put("${INBOX}/a.txt", "date: 2026-03-01\ngross: 10".bytes, Instant.parse("2026-03-01T00:00:00Z"))
        store.put("${INBOX}/b.txt", "date: 2026-03-02".bytes, Instant.parse("2026-03-02T00:00:00Z"))
        store.put("${INBOX}/c.txt", "date: 2026-03-03\n[[unclear zwei Steuersätze]]".bytes, Instant.parse("2026-03-03T00:00:00Z"))
        store.put("${INBOX}/d.txt", "date: 2026-03-04\ngross: 4".bytes, Instant.parse("2026-03-04T00:00:00Z"))
        software.refusedSources << "d.txt#"
        def task = tasks.create(setup.token, calling(setup.model, mail, "row"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "UNCLEAR"
        def result = (run.steps as List<Map>).find { it.stepId == "send" }.result as Map
        result.sent == 2
        result.failed == 1
        def calls = software.calls()
        calls.size() == 3
        def first = (calls[0].params as Map).arguments as Map
        first.date == "2026-03-01"
        first.gross == 10
        first.journal == "received"
        (first.source as String).startsWith("a.txt#")
        !calls.any { ((it.params as Map).arguments as Map).source.toString().startsWith("c.txt#") }
        (result.calls as List<Map>).find { it.source.toString().startsWith("d.txt#") }.answer.contains("refused")
        mailText(mail).contains("Booking of d.txt#")
        connection.id != null
    }

    def "call_tool: each: all sends every clear row in one call as its list rows"() {
        given:
        def setup = tasks.tenant()
        booking(setup, [type: "object", properties: [rows: [type: "array"], journal: [type: "string"]], required: ["rows"]])
        store.put("${INBOX}/a.txt", "date: 2026-03-01\ngross: 10".bytes, Instant.parse("2026-03-01T00:00:00Z"))
        store.put("${INBOX}/b.txt", "date: 2026-03-02\ngross: 2".bytes, Instant.parse("2026-03-02T00:00:00Z"))
        def task = tasks.create(setup.token, calling(setup.model, "it@example.com", "all"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        software.calls().size() == 1
        def arguments = (software.calls()[0].params as Map).arguments as Map
        (arguments.rows as List<Map>)*.date == ["2026-03-01", "2026-03-02"]
        (arguments.rows as List<Map>).every { (it.source as String).contains("#") }
        arguments.journal == "received"
    }

    def "call_tool: the software out of reach fails its first row - every row after it is marked with the same reason, not sent; the run ends unclear"() {
        given:
        def setup = tasks.tenant()
        booking(setup)
        store.put("${INBOX}/a.txt", "date: 2026-03-01".bytes, Instant.parse("2026-03-01T00:00:00Z"))
        store.put("${INBOX}/b.txt", "date: 2026-03-02".bytes, Instant.parse("2026-03-02T00:00:00Z"))
        store.put("${INBOX}/c.txt", "date: 2026-03-03".bytes, Instant.parse("2026-03-03T00:00:00Z"))
        def task = tasks.create(setup.token, calling(setup.model, "it@example.com", "row"))
        software.key = "a-key-the-connection-does-not-have"
        software.received.clear()

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "UNCLEAR"
        def result = (run.steps as List<Map>).find { it.stepId == "send" }.result as Map
        result.sent == 0
        result.failed == 3
        (result.calls as List<Map>)*.answer.every { it == "Buchhaltung refused our key - check it in the connection" }
        software.received.findAll { it.method == "initialize" }.size() == 1
    }

    def "call_tool: a software whose OAuth sign-in has to be repeated fails its rows readably - nothing is sent"() {
        given:
        def setup = tasks.tenant()
        def connection = booking(setup)
        store.put("${INBOX}/a.txt", "date: 2026-03-01".bytes, Instant.parse("2026-03-01T00:00:00Z"))
        def task = tasks.create(setup.token, calling(setup.model, "it@example.com", "row"))
        def now = Instant.now()
        grants.save(new OAuthGrant(null, setup.tenant.id as Long, connection.id as Long, GrantStatus.SIGN_IN_NEEDED,
                software.url(), "https://sign-in.example.com", "https://sign-in.example.com/authorize",
                "https://sign-in.example.com/token", null, false, false, null, "client-1", ClientIdSource.REGISTERED,
                null, "none", null, null, null, null, null, null, null, null, null, "", "refused", now, now))
        software.received.clear()

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "UNCLEAR"
        def result = (run.steps as List<Map>).find { it.stepId == "send" }.result as Map
        result.sent == 0
        (result.calls as List<Map>)*.answer.every { it == "Buchhaltung: needs a sign-in - sign in on the page Software" }
        software.calls().isEmpty()
    }

    def "call_tool: a tool turned off since the task was saved fails the run - no row is sent"() {
        given:
        def setup = tasks.tenant()
        def connection = booking(setup)
        store.put("${INBOX}/a.txt", "date: 2026-03-01".bytes, Instant.parse("2026-03-01T00:00:00Z"))
        def task = tasks.create(setup.token, calling(setup.model, "it@example.com", "row"))
        api.call(setup.token, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${connection.toolId}", [enabled: false])

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "FAILED"
        (run.run as Map).errorCode == "task.toolUnavailable"
        software.calls().isEmpty()
    }

    def "call_tool: the check names a tool not offered or not turned on, and arguments the tool does not take"() {
        given:
        def setup = tasks.tenant()
        def connection = booking(setup)

        expect:
        codes(setup, calling(setup.model, "it@example.com", "row").replace("tool: create_booking", "tool: delete_all")) == ["task.toolUnknown"]
        codes(setup, calling(setup.model, "it@example.com", "row").replace("journal: received", "journal: received\n      gross: viel")) == ["task.toolArgumentsInvalid"]
        codes(setup, calling(setup.model, "it@example.com", "all")) == ["task.toolArgumentsInvalid"]
        codes(setup, calling(setup.model, "it@example.com", "row").replace("- {name: date, type: date, required: true}", "- {name: datum, type: date}")) == ["task.toolArgumentsInvalid"]
        codes(setup, calling(setup.model, "it@example.com", "row").replace("- {name: gross, type: amount}", "- {name: gross, type: text}")) == ["task.toolArgumentsInvalid"]
        codes(setup, calling(setup.model, "it@example.com", "lots")) == ["task.eachUnknown"]

        and: "a with the tool's schema does not name is a typo - refused, it would never reach the tool"
        def typo = tasks.validate(setup.token, calling(setup.model, "it@example.com", "row")
                .replace("journal: received", "journal: received\n      jurnal: received")).errors as List<Map>
        typo*.code == ["task.toolArgumentsInvalid"]
        typo[0].params.problem == 'it takes no "jurnal" - with names an argument the tool does not have'
        codes(setup, calling(setup.model, "it@example.com", "row").replace("journal: received", "jurnal: received")) ==
                ["task.toolArgumentsInvalid"]

        when: "the tool turned off"
        api.call(setup.token, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${connection.toolId}", [enabled: false])

        then:
        codes(setup, calling(setup.model, "it@example.com", "row")) == ["task.toolUnknown"]
    }

    def "call_tool as a source: the tool's objects become rows, typed - since is the last successful run; written into a CSV, nothing twice; the unclear ones mailed"() {
        given:
        def setup = tasks.tenant()
        String mail = "buchhaltung-${UUID.randomUUID()}@example.com"
        tasks.allowWriting(setup)
        store.folders.add(LOGS + "/export/2026")
        invoicing(setup)
        software.answers.list_invoices = { Map arguments ->
            [content: [[type: "text", text: '[{"number": "RE-1", "date": "2026-01-24", "gross": 119.0, "partner": "Kunde A"},' +
                    ' {"number": "", "date": "2026-01-25", "gross": 5}]']], isError: false]
        }
        def task = tasks.create(setup.token, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: list_invoices
                with:
                  since: '{lastRun:yyyy-MM-dd}'
                fields:
                  - {name: number, type: text, required: true}
                  - {name: date, type: date, required: true}
                  - {name: gross, type: amount}
                key: number
              - id: csv
                type: write_file
                input: sent
                store: Logdateien
                file: 'export/{date:yyyy}/sent.csv'
              - id: mail
                type: send_mail
                input: sent
                if: sent.unclear > 0
                to: [${mail}]"""))

        when:
        def first = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        def second = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (first.run as Map).state == "DONE"
        def rows = first.rows as List<Map>
        rows[0].source == "Faktura/list_invoices#RE-1"
        rows[0].values == [number: "RE-1", date: "2026-01-24", gross: 119]
        rows[0].unclear == null
        rows[1].unclear == "The tool gave no number"
        def calls = software.calls()*.params*.arguments
        calls[0].since == ""
        calls[1].since == LocalDate.now(ZoneId.of("Europe/Berlin")).toString()
        def csv = csv("export/2026/sent.csv")
        csv.contains("RE-1;2026-01-24;119,00;;Faktura/list_invoices#RE-1")
        ((second.steps as List<Map>).find { it.stepId == "csv" }.result as Map).skipped == 2

        and: "the mail names the unclear row and the rows - no documents were read"
        String body = mailText(mail)
        body.contains("Faktura/list_invoices#2: The tool gave no number")
        body.contains("2 row(s), 1 of them unclear.")
        !body.contains("document(s) read")
    }

    def "call_tool as a source of documents: each listed row's file fetched by its key and read - one without a file, or one the tool fails at, is kept with its reason"() {
        given:
        def setup = tasks.tenant()
        invoicing(setup)
        software.answers.list_incoming_documents = { Map arguments ->
            [content: [[type: "text", text: '[{"id": "7"}, {"id": "8"}, {"id": "9"}]']], isError: false]
        }
        software.answers.get_document = { Map arguments ->
            arguments.id == "7"
                    ? [content: [FakeMcpSoftware.blob("file:///beleg-7.txt", "text/plain", "date: 2026-03-07".bytes)], isError: false]
                    : arguments.id == "8" ? [content: [[type: "text", text: "Kein Beleg zu " + arguments.id]], isError: false]
                    : null // a JSON-RPC error, as a server answers a file it fails at
        }
        def task = tasks.create(setup.token, sourcing("""
              - id: documents
                type: call_tool
                connection: Faktura
                tool: list_incoming_documents
                key: id
                file:
                  tool: get_document
              - id: rows
                type: extract
                model: ${setup.model.displayName}
                input: documents
                fields:
                  - {name: date, type: date}"""))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def documents = ((run.steps as List<Map>).find { it.stepId == "documents" }.result as Map).documents as List<Map>
        documents[0].name == "beleg-7.txt"
        (documents[0].source as String).startsWith("Faktura/list_incoming_documents#7#")
        documents[1].problem == "The tool get_document gave no file for 8"
        (documents[2].problem as String).contains("answered with an error")
        def rows = run.rows as List<Map>
        rows*.values == [[date: "2026-03-07"], [:], [:]]
        rows[1].unclear == "The tool get_document gave no file for 8"
        (software.calls()*.params).findAll { it.name == "get_document" }*.arguments*.id == ["7", "8", "9"]
    }

    def "call_tool as a source of documents: a file the tool links to is fetched and read - an expired link is the document's reason"() {
        given:
        def setup = tasks.tenant()
        invoicing(setup)
        software.downloads["beleg-7"] = [body: "date: 2026-03-07".bytes, type: "text/plain"]
        software.downloads["beleg-8"] = [status: 410]
        software.answers.list_incoming_documents = { Map arguments ->
            [content: [[type: "text", text: '[{"id": "7"}, {"id": "8"}]']], isError: false]
        }
        software.answers.get_document = { Map arguments ->
            [content: [FakeMcpSoftware.link(software.fileUrl("beleg-" + arguments.id), "beleg-${arguments.id}.txt".toString())],
             isError: false]
        }
        def task = tasks.create(setup.token, sourcing("""
              - id: documents
                type: call_tool
                connection: Faktura
                tool: list_incoming_documents
                key: id
                file:
                  tool: get_document
              - id: rows
                type: extract
                model: ${setup.model.displayName}
                input: documents
                fields:
                  - {name: date, type: date}"""))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def documents = ((run.steps as List<Map>).find { it.stepId == "documents" }.result as Map).documents as List<Map>
        documents[0].name == "beleg-7.txt"
        documents[1].problem == 'The tool get_document gave no file for 8 [file "beleg-8.txt" could not be fetched: ' +
                'the link has expired or the file is gone (HTTP 410)]'
        (run.rows as List<Map>)*.values == [[date: "2026-03-07"], [:]]
    }

    def "call_tool as a source of documents: a link's server failing, or a scan without an image model, fails the run - {lastRun} stays, nothing is lost"() {
        given:
        def setup = tasks.tenant()
        invoicing(setup)
        software.downloads["beleg-7"] = [status: 503]
        software.answers.list_incoming_documents = { Map arguments ->
            [content: [[type: "text", text: '[{"id": "7"}]']], isError: false]
        }
        software.answers.get_document = { Map arguments ->
            [content: [FakeMcpSoftware.link(software.fileUrl("beleg-7"), "beleg-7.txt")], isError: false]
        }
        def task = tasks.create(setup.token, sourcing("""
              - id: documents
                type: call_tool
                connection: Faktura
                tool: list_incoming_documents
                key: id
                file:
                  tool: get_document
              - id: rows
                type: extract
                model: ${setup.model.displayName}
                input: documents
                fields:
                  - {name: date, type: date}"""))

        when:
        def linkDown = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (linkDown.run as Map).state == "FAILED"
        (linkDown.run as Map).errorCode == "task.linkFailedForNow"

        when: "the file is a scan and the tenant has no image model"
        software.answers.get_document = { Map arguments ->
            [content: [FakeMcpSoftware.blob("file:///beleg-7.png", "image/png", png())], isError: false]
        }
        def scan = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (scan.run as Map).state == "FAILED"
        (scan.run as Map).errorCode == "task.documentNotYet"
    }

    def "call_tool as a source of documents: a file of the software's own it cannot read for the moment fails the run - a file it refuses is the document's reason"() {
        given:
        def setup = tasks.tenant()
        invoicing(setup)
        software.resources["dms://beleg/7"] = [uri: "dms://beleg/7", mimeType: "text/plain", text: "date: 2026-03-07"]
        software.answers.list_incoming_documents = { Map arguments ->
            [content: [[type: "text", text: '[{"id": "7"}]']], isError: false]
        }
        software.answers.get_document = { Map arguments ->
            [content: [FakeMcpSoftware.link("dms://beleg/" + link, "beleg-7.txt")], isError: false]
        }
        def link = "7"
        def task = tasks.create(setup.token, sourcing("""
              - id: documents
                type: call_tool
                connection: Faktura
                tool: list_incoming_documents
                key: id
                file:
                  tool: get_document
              - id: rows
                type: extract
                model: ${setup.model.displayName}
                input: documents
                fields:
                  - {name: date, type: date}"""))

        when: "the software fails while reading its resource"
        software.readStatus = 503
        def down = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "with {lastRun} moved on the document would never be asked for again"
        (down.run as Map).state == "FAILED"
        (down.run as Map).errorCode == "task.linkFailedForNow"

        when: "the software answers that it has no such resource"
        software.readStatus = 0
        link = "unknown"
        def refused = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (refused.run as Map).state == "DONE"
        (((refused.steps as List<Map>).find { it.stepId == "documents" }.result as Map).documents as List<Map>)[0].problem
                .contains("the server refused to read it - Resource not found")

        cleanup:
        software.readStatus = 0
        software.resources.clear()
    }

    def "call_tool as a source: an answer that is no array of objects, or more than 200, fails the run"() {
        given:
        def setup = tasks.tenant()
        invoicing(setup)
        software.answers.list_invoices = { Map arguments -> [content: [[type: "text", text: answer]], isError: false] }
        def task = tasks.create(setup.token, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: list_invoices
                fields:
                  - {name: number, type: text}
                key: number"""))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "FAILED"
        (run.run as Map).errorCode == code

        where:
        answer                                                              || code
        "Keine Rechnungen"                                                  || "task.toolAnswerInvalid"
        "[1, 2]"                                                            || "task.toolAnswerInvalid"
        "[" + (1..201).collect { '{"number": "' + it + '"}' }.join(",") + "]" || "task.toolAnswerTooLarge"
        "[" + (1..150).collect { '{"number": "' + it + '", "partner": "' + "x" * 1000 + '"}' }.join(",") + "]" || "task.toolAnswerTooLong"
    }

    def "call_tool as a source: a tool that changes something since the task was saved is not called - the run fails"() {
        given:
        def setup = tasks.tenant()
        def connection = invoicing(setup)
        def task = tasks.create(setup.token, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: list_invoices
                fields:
                  - {name: number, type: text}
                key: number"""))
        def listInvoices = (connection.tools as List<Map>).find { it.name == "list_invoices" }
        api.call(setup.token, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${listInvoices.id}", [changes: true])

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "FAILED"
        (run.run as Map).errorCode == "task.toolChanges"
        software.calls().isEmpty()
    }

    def "call_tool as a source: the software out of reach fails the run - {lastRun} stays, the next run asks for the same"() {
        given:
        def setup = tasks.tenant()
        invoicing(setup)
        software.answers.list_invoices = { Map arguments -> [content: [[type: "text", text: "[]"]], isError: false] }
        def task = tasks.create(setup.token, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: list_invoices
                with:
                  since: '{lastRun:yyyy-MM-dd}'
                fields:
                  - {name: number, type: text}
                key: number"""))
        software.key = "a-key-the-connection-does-not-have"

        when:
        def failed = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        software.key = null
        def next = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (failed.run as Map).state == "FAILED"
        (failed.run as Map).errorCode == "mcpServer.keyRefused"
        (next.run as Map).state == "DONE"
        software.calls()*.params*.arguments*.since == [""]
    }

    def "call_tool as a source of documents: a file too large for an answer is kept with its reason - the run goes on"() {
        given:
        def setup = tasks.tenant()
        invoicing(setup)
        software.answers.list_incoming_documents = { Map arguments ->
            [content: [[type: "text", text: '[{"id": "7"}, {"id": "8"}]']], isError: false]
        }
        byte[] large = new byte[6_500_000] // 8.7 MB as base64 - over the 8 MiB a message may have
        software.answers.get_document = { Map arguments ->
            arguments.id == "7"
                    ? [content: [FakeMcpSoftware.blob("file:///beleg-7.txt", "text/plain", "date: 2026-03-07".bytes)], isError: false]
                    : [content: [FakeMcpSoftware.blob("file:///beleg-8.pdf", "application/pdf", large)], isError: false]
        }
        def task = tasks.create(setup.token, documentsOf("get_document"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def documents = ((run.steps as List<Map>).find { it.stepId == "documents" }.result as Map).documents as List<Map>
        documents[0].name == "beleg-7.txt"
        documents[1].problem == "Faktura sent an answer larger than 8 MB"
    }

    def "call_tool as a source of documents: a file tool that changes something since the task was saved fails the run - it is not called"() {
        given:
        def setup = tasks.tenant()
        def connection = invoicing(setup)
        software.answers.list_incoming_documents = { Map arguments ->
            [content: [[type: "text", text: '[{"id": "7"}]']], isError: false]
        }
        def task = tasks.create(setup.token, documentsOf("get_document"))
        def getDocument = (connection.tools as List<Map>).find { it.name == "get_document" }
        api.call(setup.token, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${getDocument.id}", [changes: true])

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "FAILED"
        (run.run as Map).errorCode == "task.toolChanges"
        !software.calls()*.params*.name.contains("get_document")
    }

    def "call_tool as a source: the log names a failed run's code, never what the software answered - the run keeps it"() {
        given:
        def setup = tasks.tenant()
        invoicing(setup)
        software.answers.list_invoices = { Map arguments ->
            [content: [[type: "text", text: "Kunde Musterfirma GmbH ist gesperrt"]], isError: true]
        }
        def task = tasks.create(setup.token, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: list_invoices
                fields:
                  - {name: number, type: text}
                key: number"""))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).errorCode == "task.toolFailed"
        ((run.run as Map).errorMessage as String).contains("Musterfirma")
        def lines = RecentLogAppender.installed().entries()*.message().findAll {
            it.startsWith("Run ${(run.run as Map).id} of task ${task.id} ")
        }
        lines.size() == 1
        lines[0].endsWith(": task.toolFailed")
    }

    def "call_tool as a source: the check wants rows or documents, a key among the fields, a reading tool - a target and send_mail take rows, a condition its counts"() {
        given:
        def setup = tasks.tenant()
        invoicing(setup)

        expect:
        codes(setup, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: list_invoices
                key: number""")) == ["task.sourceFormRequired"]
        codes(setup, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: list_invoices
                fields:
                  - {name: number, type: text}
                key: nummer""")) == ["task.keyUnknown"]
        codes(setup, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: cancel_invoice
                fields:
                  - {name: number, type: text}
                key: number""")) == ["task.toolChanges"]
        codes(setup, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: list_invoices
                with:
                  since: '{lastRun:qqqq-bad}'
                fields:
                  - {name: number, type: text}
                key: number""")) == ["task.lastRunInvalid"]
        codes(setup, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: list_invoices
                with:
                  sice: '{lastRun:yyyy-MM-dd}'
                fields:
                  - {name: number, type: text}
                key: number""")) == ["task.toolArgumentsInvalid"]
        codes(setup, documentsOf("get_document\n                  with:\n                    format: pdf")) ==
                ["task.toolArgumentsInvalid"]
        codes(setup, documentsOf("get_document")) == []
        codes(setup, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: list_invoices
                fields:
                  - {name: number, type: text}
                key: number
              - id: rows
                type: extract
                model: ${setup.model.displayName}
                input: sent
                fields:
                  - {name: date, type: date}""")) == ["task.inputWrongForm"]
        codes(setup, sourcing("""
              - id: sent
                type: call_tool
                connection: Faktura
                tool: list_invoices
                fields:
                  - {name: number, type: text}
                key: number
              - id: mail
                type: send_mail
                input: sent
                if: sent.failed > 0""")) == ["task.conditionCountUnknown"]
        codes(setup, sourcing("""
              - id: documents
                type: call_tool
                connection: Faktura
                tool: list_incoming_documents
                key: id
                file:
                  tool: get_document
              - id: mail
                type: send_mail
                input: documents
                if: documents.files > 0""")) == ["task.inputWrongForm"]
    }

    private DataSource dataSource(Map setup) {
        databases.dataSource(tenants.findById(setup.tenant.id as Long).get().databaseName())
    }

    /** A task of one read_documents step on the store "Logdateien" (folder /logs). */
    private static String documents(Map options) {
        """\
        version: 1
        name: Belege ${UUID.randomUUID()}
        notify: [it@example.com]
        steps:
          - id: documents
            type: read_documents
            store: Logdateien
            folder: ${options.folder}
            subfolders: ${options.subfolders ?: false}
            pattern: ${options.pattern}
        """.stripIndent()
    }

    /** read_documents of the inbox, extract with these fields, a mail when a row is unclear. */
    private static String extracting(Map model, String recipient, String fields = """
              - {name: kind, type: choice, values: [invoice, credit_note, other]}
              - {name: date, type: date, required: true}
              - {name: number, type: text}
              - {name: partner, type: text}
              - {name: net, type: amount}
              - {name: gross, type: amount}""") {
        """\
        version: 1
        name: Rechnungen ${UUID.randomUUID()}
        notify: [${recipient}]
        steps:
          - id: documents
            type: read_documents
            store: Logdateien
            folder: inbox
            pattern: '*.txt'
          - id: rows
            type: extract
            model: ${model.displayName}
            input: documents
            instructions: The partner is always the other party.
            fields:${fields}
          - id: mail
            type: send_mail
            if: rows.unclear > 0
            input: [rows]
            subject: Belege prüfen
        """.stripIndent()
    }

    /** read_documents, extract (date, gross, partner), write_file into export/{date:yyyy}/rows.csv, a mail of both. */
    private static String writing(Map model, String recipient) {
        """\
        version: 1
        name: Belege ${UUID.randomUUID()}
        notify: [${recipient}]
        steps:
          - id: documents
            type: read_documents
            store: Logdateien
            folder: inbox
            pattern: '*.txt'
          - id: rows
            type: extract
            model: ${model.displayName}
            input: documents
            fields:
              - {name: date, type: date, required: true}
              - {name: gross, type: amount}
              - {name: partner, type: text}
          - id: csv
            type: write_file
            input: rows
            store: Logdateien
            file: 'export/{date:yyyy}/rows.csv'
            dateFormat: dd.MM.yyyy
          - id: mail
            type: send_mail
            input: [rows, csv]
            subject: Belege erfasst
        """.stripIndent()
    }

    /** The CSV as the store holds it, below the store's folder. */
    private String csv(String file) {
        new String(store.files[LOGS + "/" + file], "UTF-8")
    }

    /** The connected software "Buchhaltung" with one tool create_booking (it changes something), turned on. */
    private Map booking(Map setup, Map schema = [type: "object", properties: [date: [type: "string"], gross: [type: "number"],
            source: [type: "string"], journal: [type: "string"]], required: ["date", "source"]]) {
        software.tools = [[name: "create_booking", description: "Books a document", inputSchema: schema]]
        def connection = api.call(setup.token, "POST", "/tenant/admin/mcp-connections",
                [name: "Buchhaltung", baseUrl: software.url()]) as Map
        def tool = (connection.tools as List<Map>).find { it.name == "create_booking" }
        api.call(setup.token, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${tool.id}", [enabled: true])
        [id: connection.id, toolId: tool.id]
    }

    /** read_documents, extract (date, gross), call_tool create_booking with every row - or all at once. */
    private static String calling(Map model, String recipient, String each) {
        """\
        version: 1
        name: Buchen ${UUID.randomUUID()}
        notify: [${recipient}]
        steps:
          - id: documents
            type: read_documents
            store: Logdateien
            folder: inbox
            pattern: '*.txt'
          - id: rows
            type: extract
            model: ${model.displayName}
            input: documents
            fields:
              - {name: date, type: date, required: true}
              - {name: gross, type: amount}
          - id: send
            type: call_tool
            input: rows
            connection: Buchhaltung
            tool: create_booking
            each: ${each}
            with:
              journal: received
        """.stripIndent()
    }

    private List codes(Map setup, String source) {
        (tasks.validate(setup.token, source).errors as List<Map>)*.code
    }

    /** The connected software "Faktura": reading tools list_invoices, list_incoming_documents, get_document - and cancel_invoice, which changes. */
    private Map invoicing(Map setup) {
        def reads = [readOnlyHint: true]
        software.tools = [
                [name: "list_invoices", description: "Lists invoices", annotations: reads,
                 inputSchema: [type: "object", properties: [since: [type: "string"]]]],
                [name: "list_incoming_documents", description: "Lists documents received", annotations: reads,
                 inputSchema: [type: "object", properties: [since: [type: "string"]]]],
                [name: "get_document", description: "One document's file", annotations: reads,
                 inputSchema: [type: "object", properties: [id: [type: "string"]], required: ["id"]]],
                [name: "cancel_invoice", description: "Cancels an invoice",
                 inputSchema: [type: "object", properties: [number: [type: "string"]]]]]
        def connection = api.call(setup.token, "POST", "/tenant/admin/mcp-connections",
                [name: "Faktura", baseUrl: software.url()]) as Map
        (connection.tools as List<Map>).each {
            api.call(setup.token, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${it.id}", [enabled: true])
        }
        connection
    }

    /** list_incoming_documents of "Faktura" as a source of documents, each fetched with the file tool. */
    private static String documentsOf(String fileTool) {
        sourcing("""
              - id: documents
                type: call_tool
                connection: Faktura
                tool: list_incoming_documents
                key: id
                file:
                  tool: ${fileTool}""")
    }

    /** A task of these steps, written as they are. */
    private static String sourcing(String steps) {
        "version: 1\nname: Faktura ${UUID.randomUUID()}\nnotify: [it@example.com]\nsteps:" +
                steps.stripIndent().replaceAll(/(?m)^/, "  ").replaceFirst(/^\s*\n/, "\n")
    }

    /** Every text part of the mails to this address, as one text. */
    private static String mailText(String address) {
        TaskApi.mailsTo(address).collect { message -> parts(message.content) }.join("\n")
    }

    private static String parts(Object content) {
        if (content instanceof Multipart) {
            return (0..<content.count).collect { parts(content.getBodyPart(it).content) }.join("\n")
        }
        String.valueOf(content)
    }

    private Map tool(String key, String name, Map arguments) {
        Map message = [jsonrpc: "2.0", id: UUID.randomUUID().toString(), method: "tools/call",
                       params : [name: name, arguments: arguments]]
        (client.toBlocking().exchange(HttpRequest.POST("/mcp", message).bearerAuth(key), Map).body() as Map).result as Map
    }

    private static byte[] png() {
        def out = new ByteArrayOutputStream()
        ImageIO.write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "png", out)
        out.toByteArray()
    }
}
