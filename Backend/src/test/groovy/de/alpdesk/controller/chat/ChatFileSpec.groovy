package de.alpdesk.controller.chat

import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.chat.ChatFileService
import de.alpdesk.services.tenant.TenantDatabases
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import spock.lang.Shared
import spock.lang.Specification

import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Files in the chat: uploaded to a conversation (text read by Tika, images and scanned PDF pages sent as images), sent
 * with every question, created by the model for download - and gone with their conversation.
 */
@MicronautTest(transactional = false)
class ChatFileSpec extends Specification {

    static final Path STORAGE = Path.of("build/test-storage")

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
    ChatFileService chatFiles

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "uploaded files start a conversation; the question lists them and the model reads them with its tool"() {
        given:
        def model = api.chatModel()
        def tenant = api.tenant()
        def token = userWithKeys(tenant, model)

        when: "a text, a photo and a scanned PDF of two pages are uploaded without a conversation"
        def uploaded = api.chatUpload(token, ["notiz.txt": "Die Anlage startet um 15 Uhr.".getBytes("UTF-8"),
                                              "foto.png" : png(3000, 1000), "scan.pdf": scannedPdf(2)])

        then:
        uploaded.conversation.title == "Dateien: notiz.txt"
        uploaded.files*.fileName == ["notiz.txt", "foto.png", "scan.pdf"]
        uploaded.files*.origin == ["UPLOADED"] * 3
        uploaded.files*.contentType == ["text/plain", "image/png", "application/pdf"]
        uploaded.files*.imageCount == [0, 1, 2]

        and: "the photo is stored scaled down to 2048 pixels for the model"
        def stored = Files.walk(STORAGE.resolve("tenants/${tenant.id}/chat")).filter { it.fileName.toString() == "image-1.jpg" }.toList()
        stored.collect { ImageIO.read(it.toFile()).width }.contains(2048)

        when:
        def first = api.ask(token, [conversationId: uploaded.conversation.id, modelId: model.id, message: "Was steht drin?"])
        def second = api.ask(token, [conversationId: uploaded.conversation.id, modelId: model.id, message: "Und sonst?"])

        then: "the model read the text as text, the photo and the scan's pages as images"
        first.answer.content == "Antwort auf: Was steht drin? (Auszüge: 0) (Bilder: 3) (Dateien: notiz.txt)"
        second.answer.content.endsWith("(Bilder: 3) (Dateien: notiz.txt)")
        def asked = fake.chats.first().messages.last() as UserMessage
        def list = (asked.contents().first() as dev.langchain4j.data.message.TextContent).text()
        list.contains('"notiz.txt" (text/plain, ')
        list.contains('"scan.pdf" (application/pdf, ') && list.contains("scanned: 2 page images")
        !list.contains("Die Anlage startet um 15 Uhr.")
        fake.chats.first().tools.contains("read_file")
        def images = fake.chats.last().messages.find { it instanceof UserMessage && FakeAiModels.isToolImages(it as UserMessage) } as UserMessage
        images.contents().count { it instanceof ImageContent } == 3

        and:
        (api.call(token, "GET", "/tenant/chat/conversations/${uploaded.conversation.id}/files") as List)*.fileName ==
                ["notiz.txt", "foto.png", "scan.pdf"]
    }

    def "a PDF the model reads as itself follows the tool results as the original file"() {
        given:
        def model = api.chatModel("GEMINI", [readsPdf: true])
        def token = userWithKeys(api.tenant(), model)
        def uploaded = api.chatUpload(token, ["scan.pdf": scannedPdf(1), "notiz.txt": "Text".getBytes()])

        when:
        def result = api.ask(token, [conversationId: uploaded.conversation.id, modelId: model.id, message: "Lies es [[original]]"])

        then:
        result.answer.content.endsWith("(PDFs: 1) (Dateien: notiz.txt)")
        def sent = fake.chats.last().messages.find { it instanceof UserMessage && FakeAiModels.isToolImages(it as UserMessage) } as UserMessage
        def pdf = sent.contents().find { it instanceof dev.langchain4j.data.message.PdfFileContent } as dev.langchain4j.data.message.PdfFileContent
        new String(Base64.decoder.decode(pdf.pdfFile().base64Data()), 0, 5) == "%PDF-"
    }

    def "files are added to an existing conversation"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)
        def conversation = api.ask(token, [modelId: model.id, message: "Hallo"]).conversation

        when:
        api.chatUpload(token, ["a.txt": "Erste Datei".getBytes("UTF-8")], conversation.id)
        api.chatUpload(token, ["b.md": "# Zweite Datei".getBytes("UTF-8")], conversation.id)

        then:
        api.ask(token, [conversationId: conversation.id, modelId: model.id, message: "Welche?"]).answer.content
                .endsWith("(Dateien: a.txt, b.md)")
    }

    def "the model creates a #format file the user downloads; the next question is sent with it"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)
        def uploaded = api.chatUpload(token, ["wartung.txt": "Filter alle 3 Monate tauschen.".getBytes("UTF-8")])

        when:
        def result = api.ask(token, [conversationId: uploaded.conversation.id, modelId: model.id,
                                     message: "Überarbeite sie [[create_file neu " + format + "]]"])

        then: "the tool was offered, the file created and attached to the answer"
        fake.chats.first().tools == ["read_file", "create_file"]
        result.answer.content.startsWith("Fertig: The file \"neu." + extension + "\" was created.")
        result.answer.files.size() == 1
        def file = result.answer.files[0]
        file.fileName == "neu." + extension
        file.origin == "GENERATED"
        file.contentType.startsWith(type)

        when:
        def download = api.download(token, "/tenant/chat/files/${file.id}/content")

        then:
        download.status == HttpStatus.OK
        download.header("Content-Disposition").contains("neu." + extension)
        check(download.body())

        and: "the history shows the file below the answer, the conversation lists it"
        def messages = api.call(token, "GET", "/tenant/chat/conversations/${uploaded.conversation.id}/messages") as List<Map>
        messages[1].files*.id == [file.id]
        (api.call(token, "GET", "/tenant/chat/conversations/${uploaded.conversation.id}/files") as List)*.origin == ["UPLOADED", "GENERATED"]

        and: "the next question is sent with it, and the history says which answer created it"
        api.ask(token, [conversationId: uploaded.conversation.id, modelId: model.id, message: "Und?"]).answer.content
                .endsWith("(Dateien: wartung.txt, neu." + extension + ")")
        (fake.chats.last().messages[2] as dev.langchain4j.data.message.AiMessage).text()
                .endsWith("[Created with a tool: neu." + extension + "]")

        where:
        format | extension | type                                                                        | check
        "DOCX" | "docx"    | "application/vnd.openxmlformats-officedocument.wordprocessingml.document" | { byte[] b -> new XWPFWordExtractor(new XWPFDocument(new ByteArrayInputStream(b))).text.contains("Aus: wartung.txt") }
        "PDF"  | "pdf"     | "application/pdf"                                                           | { byte[] b -> new String(b, 0, 5) == "%PDF-" }
        "XLSX" | "xlsx"    | "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"       | { byte[] b -> b[0] == (byte) 'P' && b[1] == (byte) 'K' }
        "MD"   | "md"      | "text/markdown"                                                             | { byte[] b -> new String(b, "UTF-8").startsWith("# Überarbeitet") }
    }

    def "the created file streams too: the answer's files come with the final event"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)

        when:
        def events = api.askEvents(token, [modelId: model.id, message: "Bitte [[create_file liste.csv CSV]]"])

        then:
        events.findAll { it.event == "delta" }.collect { it.data.text }.join("").startsWith("Ich erstelle die Datei.\n\nFertig:")
        events.last().event == "done"
        events.last().data.answer.content.startsWith("Fertig:")
        events.last().data.answer.files*.fileName == ["liste.csv"]

        and: "its rules say the chat shows the file below the answer"
        (fake.chats.first().messages[0] as SystemMessage).text().contains(
                "Never write a link or path to a created file or image: the application shows it below your answer.")
    }

    def "an answer that fails after creating a file leaves neither the file nor its content behind"() {
        given:
        def model = api.chatModel()
        def tenant = api.tenant()
        def token = userWithKeys(tenant, model)
        fake.failAfterTool = true

        when:
        def events = api.askEvents(token, [modelId: model.id, message: "Bitte [[create_file a.txt TXT]]"])

        then:
        events.last().event == "error"
        api.call(token, "GET", "/tenant/chat/conversations") == []
        !Files.exists(STORAGE.resolve("tenants/${tenant.id}/chat")) ||
                Files.walk(STORAGE.resolve("tenants/${tenant.id}/chat")).noneMatch { Files.isRegularFile(it) }
    }

    def "a file whose content went missing is told to the model - the answer goes on"() {
        given:
        def model = api.chatModel()
        def tenant = api.tenant()
        def token = userWithKeys(tenant, model)
        def uploaded = api.chatUpload(token, ["foto.png": png(300, 100)])
        Files.walk(STORAGE.resolve("tenants/${tenant.id}/chat")).filter { Files.isRegularFile(it) }.toList()
                .each { Files.delete(it) }

        when:
        def done = api.ask(token, [conversationId: uploaded.conversation.id, modelId: model.id, message: "Was zeigt das Foto?"])

        then:
        done.answer.content.contains("(Error: The file's content is missing)")
    }

    def "a created file leaves the pending list once its row is stored - a later failure keeps its content"() {
        given:
        def tenant = api.tenant()
        def token = userWithKeys(tenant)
        def conversation = api.chatUpload(token, ["notiz.txt": "Text".getBytes()]).conversation.id as Long
        def dataSource = databases.dataSource(tenants.findById(tenant.id as Long).get().databaseName())
        def stored = new ChatFileService.PendingFile("a.txt", "text/plain", 4, "tenants/${tenant.id}/chat/a/", "Text", 0)
        def broken = new ChatFileService.PendingFile(null, "text/plain", 4, "tenants/${tenant.id}/chat/b/", "Text", 0)
        def pending = [stored, broken]

        when:
        chatFiles.attach(dataSource, conversation, null, pending)

        then:
        thrown(RuntimeException)
        pending == [broken]
    }

    def "uploads are refused: #reason"() {
        given:
        def token = userWithKeys(api.tenant())

        expect:
        api.chatUploadStatus(token, files) == status

        where:
        reason                       | files                                                                  | status
        "an archive"                 | ["a.zip": zip()]                                                       | HttpStatus.UNSUPPORTED_MEDIA_TYPE
        // ffmpeg would follow a playlist to the network or to the server's own files (other tenants' media).
        "an HLS playlist"            | ["a.m3u": "#EXTM3U\n#EXTINF:1,\n/app/files/x.mp3\n".getBytes()]       | HttpStatus.UNSUPPORTED_MEDIA_TYPE
        // With CRLF Tika calls it audio/x-mpegurl - whatever the name.
        "an HLS playlist with CRLF"  | ["a.m3u": "#EXTM3U\r\n#EXTINF:1,\r\n/app/files/x.mp3\r\n".getBytes()] | HttpStatus.UNSUPPORTED_MEDIA_TYPE
        "a CRLF playlist as MP3"     | ["a.mp3": "#EXTM3U\r\n#EXTINF:1,\r\n/app/files/x.mp3\r\n".getBytes()] | HttpStatus.UNSUPPORTED_MEDIA_TYPE
        "a playlist (PLS)"           | ["a.pls": "[playlist]\nFile1=http://127.0.0.1/x.mp3\n".getBytes()]    | HttpStatus.UNSUPPORTED_MEDIA_TYPE
        "an empty file"              | ["a.txt": new byte[0]]                                                 | HttpStatus.BAD_REQUEST
        "a document without text"    | ["a.txt": "   ".getBytes()]                                            | HttpStatus.BAD_REQUEST
        "a scan of too many pages"   | ["scan.pdf": scannedPdf(21)]                                           | HttpStatus.BAD_REQUEST
        "too much text"              | ["a.txt": ("Wort " * 400_001).getBytes()]                              | HttpStatus.BAD_REQUEST
        "too many files at once"     | (1..11).collectEntries { ["f${it}.txt".toString(), "Text".getBytes()] } | HttpStatus.BAD_REQUEST
    }

    def "files are their conversation's user's alone"() {
        given:
        def tenant = api.tenant()
        def owner = userWithKeys(tenant)
        def colleague = api.tenantAdminToken(tenant.id)
        def uploaded = api.chatUpload(owner, ["privat.txt": "Geheim".getBytes()])
        def fileId = uploaded.files[0].id
        def key = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Fachsoftware"]).plainKey

        expect:
        api.status(colleague, "GET", "/tenant/chat/files/${fileId}/content") == HttpStatus.NOT_FOUND
        api.status(colleague, "DELETE", "/tenant/chat/files/${fileId}") == HttpStatus.NOT_FOUND
        api.status(colleague, "GET", "/tenant/chat/conversations/${uploaded.conversation.id}/files") == HttpStatus.NOT_FOUND
        api.chatUploadStatus(colleague, ["x.txt": "x".getBytes()], uploaded.conversation.id) == HttpStatus.NOT_FOUND
        api.status(key, "GET", "/api/v1/chat/files/${fileId}/content") == HttpStatus.NOT_FOUND
    }

    def "deleting a file, its conversation or the tenant deletes the stored content"() {
        given:
        def tenant = api.tenant()
        def token = userWithKeys(tenant)
        def first = api.chatUpload(token, ["a.txt": "Eins".getBytes(), "b.txt": "Zwei".getBytes()])
        def other = api.chatUpload(token, ["c.txt": "Drei".getBytes()])
        def tenantFiles = STORAGE.resolve("tenants/${tenant.id}")

        when:
        def deleted = api.status(token, "DELETE", "/tenant/chat/files/${first.files[0].id}")

        then:
        deleted == HttpStatus.NO_CONTENT
        storedFiles(tenantFiles) == 2
        (api.call(token, "GET", "/tenant/chat/conversations/${first.conversation.id}/files") as List)*.fileName == ["b.txt"]

        when:
        api.status(token, "DELETE", "/tenant/chat/conversations/${first.conversation.id}")

        then:
        storedFiles(tenantFiles) == 1

        when:
        api.status(api.adminToken, "DELETE", "/admin/tenants/${tenant.id}")

        then:
        !Files.exists(tenantFiles)
        other != null
    }

    def "deleting a user deletes its conversations and their stored files - not a colleague's"() {
        given:
        def tenant = api.tenant()
        def model = api.chatModel()
        userWithKeys(tenant, model)
        def leaving = api.user(tenant.id)
        def colleague = api.userToken(tenant.id)
        api.chatUpload(leaving.token, ["a.txt": "Eins".getBytes()])
        api.ask(leaving.token, [modelId: model.id, message: "Hallo"])
        def kept = api.chatUpload(colleague, ["b.txt": "Zwei".getBytes()])
        def tenantFiles = STORAGE.resolve("tenants/${tenant.id}")

        expect:
        storedFiles(tenantFiles) == 2

        when:
        def status = api.status(api.adminToken, "DELETE", "/admin/tenants/${tenant.id}/users/${leaving.id}")

        then:
        status == HttpStatus.NO_CONTENT
        storedFiles(tenantFiles) == 1
        conversationsOf(tenant, leaving.id) == 0
        (api.call(colleague, "GET", "/tenant/chat/conversations") as List)*.id == [kept.conversation.id]
    }

    def "the client software uploads, asks with its files and downloads what the model created"() {
        given:
        def model = api.chatModel()
        def tenant = api.tenant()
        userWithKeys(tenant, model)
        def key = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Fachsoftware"]).plainKey

        when:
        def uploaded = api.chatUpload(key, ["preise.csv": "Artikel;Preis\nSchraube;80".getBytes()], null, "/api/v1/chat/files")
        def result = api.call(key, "POST", "/api/v1/chat", [conversationId: uploaded.conversation.id, modelId: model.id,
                                                            message: "Als Tabelle [[create_file preise.xlsx XLSX]]"])

        then:
        result.answer.files*.fileName == ["preise.xlsx"]
        api.download(key, "/api/v1/chat/files/${result.answer.files[0].id}/content").status == HttpStatus.OK
        (api.call(key, "GET", "/api/v1/chat/conversations/${uploaded.conversation.id}/files") as List)*.fileName ==
                ["preise.csv", "preise.xlsx"]
        api.status(key, "DELETE", "/api/v1/chat/files/${uploaded.files[0].id}") == HttpStatus.NO_CONTENT
    }

    private String userWithKeys(Map tenant, Map... models) {
        api.allow(tenant.id, models)
        def token = api.tenantAdminToken(tenant.id)
        ["ANTHROPIC", "OPENAI", "GEMINI"].each { api.call(token, "PUT", "/tenant/admin/provider-keys/${it}", [apiKey: "own-key-of-" + it]) }
        token
    }

    private int conversationsOf(Map tenant, Object userId) {
        def databaseName = tenants.findById(tenant.id as Long).get().databaseName()
        databases.dataSource(databaseName).connection.withCloseable { connection ->
            def result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM conversation WHERE user_id = ${userId}")
            result.next()
            result.getInt(1)
        }
    }

    private static long storedFiles(Path directory) {
        Files.exists(directory) ? Files.walk(directory).filter { Files.isRegularFile(it) && it.fileName.toString() == "file" }.count() : 0
    }

    private static byte[] png(int width, int height) {
        def image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        def out = new ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        out.toByteArray()
    }

    /** A PDF whose pages are only images - like a scan, without a text layer. */
    private static byte[] scannedPdf(int pages) {
        def document = new PDDocument()
        def image = LosslessFactory.createFromImage(document, new BufferedImage(200, 280, BufferedImage.TYPE_INT_RGB))
        pages.times {
            def page = new PDPage()
            document.addPage(page)
            new PDPageContentStream(document, page).withCloseable { it.drawImage(image, 50, 50, 200, 280) }
        }
        def out = new ByteArrayOutputStream()
        document.save(out)
        document.close()
        out.toByteArray()
    }

    private static byte[] zip() {
        def out = new ByteArrayOutputStream()
        new ZipOutputStream(out).withCloseable { zip ->
            zip.putNextEntry(new ZipEntry("inhalt.txt"))
            zip.write("Text".getBytes())
            zip.closeEntry()
        }
        out.toByteArray()
    }
}
