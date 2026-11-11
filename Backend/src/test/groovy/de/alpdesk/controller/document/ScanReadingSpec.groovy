package de.alpdesk.controller.document

import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.document.DocumentIndexer
import de.alpdesk.services.document.DocumentService
import de.alpdesk.services.tenant.TenantDatabases
import de.alpdesk.storage.FileStorage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.imageio.ImageIO
import javax.sql.DataSource
import java.awt.image.BufferedImage
import java.security.MessageDigest

/**
 * Images and scanned pages of the company's documents are read by the catalog's scan reader before they are embedded:
 * an image as a whole, a PDF page by page - only the pages with (almost) no text. Without the scan reader an image is
 * refused and a scan without text too.
 */
@MicronautTest(transactional = false)
class ScanReadingSpec extends Specification {

    static final String TEXT_PAGE = "Methodischer Aufbau im Slalom: frei fahren, Korridor eingrenzen, Rhythmus imitieren, " +
            "Stummel und Kurzstangen, dann der ganze Lauf mit Zeitnahme und Videoanalyse am Ende des Tages. " +
            "Zum Abschluss Auslaufen und eine kurze Besprechung in der Gruppe."
    /** A page of the PDF helper that shows an image declaring 40,000 x 40,000 pixels - refused when it is rendered. */
    static final String HUGE_SCAN = "<huge scan>"
    /** A page whose image has a broken colour space (a Lab white point of one value) - PDFBox fails drawing it. */
    static final String BROKEN_SCAN = "<broken scan>"

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
    FileStorage storage

    @Inject
    DocumentIndexer indexer

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "an image is read by the scan reader and embedded with what it read"() {
        given:
        def tenant = api.tenant()
        def token = adminWithModels(tenant, true)

        when:
        def uploaded = api.uploadFile(token, "kurvenmitte.png", png()) as Map
        def document = api.awaitDocument(token, uploaded.id)

        then:
        document.status == "READY"
        document.contentType == "image/png"
        fake.chats.size() == 1
        def read = fake.chats[0]
        (read.messages[0] as SystemMessage).text().startsWith("You turn an image")
        (read.messages[1] as UserMessage).contents().any { it instanceof ImageContent }
        read.tools == []

        when: "the chat finds what the image model read"
        def chat = api.chatModel()
        api.allow(tenant.id, chat)
        def answer = api.ask(token, [modelId: chat.id, message: "Skizze mit Toren und Abständen"])

        then:
        answer.sources == ["kurvenmitte.png"]
    }

    def "a run that finds the previous version's file under the new version's row reads nothing and fails; the new file is read"() {
        given: "an image replaced: its row already the new version's, the stored file still the previous one"
        def tenant = api.tenant()
        def token = adminWithModels(tenant, true)
        def previous = png(300, 200)
        def replacement = png(320, 200)
        def document = api.awaitDocument(token, (api.uploadFile(token, "skizze.png", previous) as Map).id)
        def key = DocumentService.originalKey(tenant.id as Long, document.id as Long)
        execute(tenant, "UPDATE document SET content_hash = '${sha256(replacement)}', reads_images = TRUE, " +
                "status = 'PENDING' WHERE id = ${document.id}")
        storage.put(key, previous, "image/png")
        fake.reset()

        when:
        indexer.submit(tenant.id as Long, document.id as Long)
        afterTheRun(tenant, document)

        then: "the previous file is not read and stays - failed, not waiting for a file that may never come"
        fake.chats.isEmpty()
        query(tenant, "SELECT reads_images FROM document WHERE id = ${document.id}") == true
        query(tenant, "SELECT status FROM document WHERE id = ${document.id}") == "FAILED"
        query(tenant, "SELECT error FROM document WHERE id = ${document.id}") ==
                "The document was replaced while it was read - please upload it again"
        storage.open(key).isPresent()

        when: "the new file is stored and its own run comes - as after a replace whose file came right after its row"
        storage.put(key, replacement, "image/png")
        indexer.submit(tenant.id as Long, document.id as Long)

        then: "waited for READY itself: awaitDocument would stop at the first run's FAILED, before this run has begun"
        new PollingConditions(timeout: 15).eventually {
            assert query(tenant, "SELECT status FROM document WHERE id = ${document.id}") == "READY"
        }
        fake.chats.size() == 1
        !storage.open(key).isPresent()
    }

    def "a PDF is read page by page - only its pages without text"() {
        given:
        def tenant = api.tenant()
        def token = adminWithModels(tenant, true)

        when:
        def uploaded = api.uploadFile(token, "slalom.pdf", pdf([TEXT_PAGE, null, TEXT_PAGE])) as Map
        api.awaitDocument(token, uploaded.id)

        then: "one call for the scanned page"
        fake.chats.size() == 1
        ((fake.chats[0].messages[1] as UserMessage).contents()[0] as dev.langchain4j.data.message.TextContent).text() ==
                "Page 2 of \"slalom.pdf\":"
        api.awaitDocument(token, uploaded.id).status == "READY"

        when: "embedding it again (another model) does not read it again"
        def other = api.embeddingModel(32)
        api.allow(tenant.id, other)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: other.id])
        api.awaitDocument(token, uploaded.id)

        then:
        fake.chats.size() == 1
    }

    def "a scanned page that cannot be rendered (#reason) keeps its text - the other pages are read and the document is ready"() {
        given:
        def tenant = api.tenant()
        def token = adminWithModels(tenant, true)

        when: "page 2 cannot be rendered - before, the whole document failed after reading page 1"
        def uploaded = api.uploadFile(token, "plan.pdf", pdf([null, page, null])) as Map
        def document = api.awaitDocument(token, uploaded.id)

        then:
        document.status == "READY"
        fake.chats.collect { ((it.messages[1] as UserMessage).contents()[0] as dev.langchain4j.data.message.TextContent).text() } ==
                ["Page 1 of \"plan.pdf\":", "Page 3 of \"plan.pdf\":"]

        where:
        reason                                    | page
        "an image larger than a page may hold"    | HUGE_SCAN
        "a broken colour space PDFBox cannot draw" | BROKEN_SCAN
    }

    def "a PDF with text on every page is not read by the image model"() {
        given:
        def token = adminWithModels(api.tenant(), true)

        when:
        def uploaded = api.uploadFile(token, "text.pdf", pdf([TEXT_PAGE, TEXT_PAGE])) as Map

        then:
        api.awaitDocument(token, uploaded.id).status == "READY"
        fake.chats.isEmpty()
    }

    def "without a scan reader an image is refused (409) and a scan without text (400); in an archive both are skipped"() {
        given:
        def token = adminWithModels(api.tenant(), false)

        expect:
        api.uploadFile(token, "foto.png", png()) == HttpStatus.CONFLICT
        api.uploadFile(token, "scan.pdf", pdf([null])) == HttpStatus.BAD_REQUEST

        when:
        def files = api.importArchive(token, ["foto.png": png(), "scan.pdf": pdf([null])]) as List<Map>

        then:
        files*.outcome == ["SKIPPED", "SKIPPED"]
        files[0].detail.startsWith("Images are read by the model reading images and scans")
        files[1].detail == "The document contains no text"
    }

    def "the scan reader must be assigned to the tenant"() {
        given:
        def tenant = api.tenant()
        def token = adminWithModels(tenant, false)
        api.chatModel("GEMINI", [readsScans: true])

        expect:
        api.uploadFile(token, "foto.png", png()) == HttpStatus.CONFLICT
    }

    def "one chat model at most reads scans"() {
        when:
        def first = api.chatModel("GEMINI", [readsScans: true])
        def second = api.chatModel("OPENAI", [readsScans: true])
        def models = (api.admin("GET", "/admin/models") as List<Map>).collectEntries { [(it.id): it] }

        then:
        models[second.id].readsScans
        !models[first.id].readsScans

        expect: "an embedding model does not read scans"
        api.status(api.adminToken, "POST", "/admin/models", [provider: "OPENAI", kind: "EMBEDDING", modelName: "e-" + AiApi.unique(),
                displayName: "E", dimension: 8, inputPrice: 1, outputPrice: 0, readsScans: true]) == HttpStatus.BAD_REQUEST
    }

    /** A tenant admin with an embedding model, keys for OpenAI and Gemini, and - if asked - the scan reader assigned. */
    private String adminWithModels(Map tenant, boolean scanReader) {
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        api.call(token, "PUT", "/tenant/admin/provider-keys/GEMINI", [apiKey: "own-gemini-key-12"])
        api.call(token, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        if (scanReader) {
            api.allow(tenant.id, api.chatModel("GEMINI", [readsScans: true]))
        }
        token
    }

    private static byte[] png(int width = 300, int height = 200) {
        def out = new ByteArrayOutputStream()
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out)
        out.toByteArray()
    }

    private static String sha256(byte[] content) {
        MessageDigest.getInstance("SHA-256").digest(content).encodeHex().toString()
    }

    /** Waits until the document's run has started (it holds the tenant's lock from then on) and ended. */
    private void afterTheRun(Map tenant, Map document) {
        def deadline = System.currentTimeMillis() + 10_000
        while (query(tenant, "SELECT status FROM document WHERE id = ${document.id}") == "PENDING") {
            assert System.currentTimeMillis() < deadline
            Thread.sleep(20)
        }
        indexer.withTenantLock(tenant.id as Long) { null }
    }

    private void execute(Map tenant, String sql) {
        dataSource(tenant).connection.withCloseable { connection -> connection.createStatement().execute(sql) }
    }

    private Object query(Map tenant, String sql) {
        dataSource(tenant).connection.withCloseable { connection ->
            def result = connection.createStatement().executeQuery(sql)
            result.next() ? result.getObject(1) : null
        }
    }

    private DataSource dataSource(Map tenant) {
        databases.dataSource(tenants.findById(tenant.id as Long).get().databaseName())
    }

    /**
     * A PDF of these pages: a page's text, null for a page that is only an image - like a scan -, HUGE_SCAN for one
     * whose image is too large to render, or BROKEN_SCAN for one whose image PDFBox cannot draw.
     */
    static byte[] pdf(List<String> pages) {
        def document = new PDDocument()
        def image = LosslessFactory.createFromImage(document, new BufferedImage(200, 280, BufferedImage.TYPE_INT_RGB))
        pages.each { text ->
            def page = new PDPage()
            document.addPage(page)
            new PDPageContentStream(document, page).withCloseable { content ->
                if (text == null) {
                    content.drawImage(image, 50, 50, 200, 280)
                } else if (text == HUGE_SCAN) {
                    def huge = LosslessFactory.createFromImage(document, new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB))
                    huge.COSObject.setInt(COSName.WIDTH, 40_000)
                    huge.COSObject.setInt(COSName.HEIGHT, 40_000)
                    content.drawImage(huge, 50, 50, 200, 280)
                } else if (text == BROKEN_SCAN) {
                    def broken = LosslessFactory.createFromImage(document, new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB))
                    def lab = new COSDictionary()
                    lab.setItem(COSName.WHITE_POINT, new COSArray([COSInteger.get(1)]))
                    broken.COSObject.setItem(COSName.COLORSPACE, new COSArray([COSName.LAB, lab]))
                    content.drawImage(broken, 50, 50, 200, 280)
                } else {
                    content.beginText()
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9)
                    content.newLineAtOffset(40, 700)
                    content.showText(text)
                    content.endText()
                }
            }
        }
        def out = new ByteArrayOutputStream()
        document.save(out)
        document.close()
        out.toByteArray()
    }
}
