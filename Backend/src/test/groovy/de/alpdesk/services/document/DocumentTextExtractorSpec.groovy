package de.alpdesk.services.document

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.alpdesk.services.common.ServiceException
import io.micronaut.http.HttpStatus
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.slf4j.LoggerFactory
import spock.lang.Specification

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Only the agreed file types are read - recognised by their content, not their name. */
class DocumentTextExtractorSpec extends Specification {

    DocumentTextExtractor extractor = new DocumentTextExtractor()

    def "reading documents logs no parser Tika failed to load - junrar is excluded on purpose"() {
        given:
        def tikaLoader = (Logger) LoggerFactory.getLogger("org.apache.tika.config.ServiceLoader")
        def logged = new ListAppender<ILoggingEvent>()
        logged.start()
        tikaLoader.addAppender(logged)

        when:
        def reader = new DocumentTextExtractor()
        reader.extract("info.docx", docx("Die Werkstatt ist ab 15 Uhr offen."))
        reader.extract("info.pdf", pdf("Die Werkstatt ist ab 15 Uhr offen."))

        then:
        logged.list.findAll { it.level.isGreaterOrEqual(Level.WARN) }.isEmpty()

        cleanup:
        tikaLoader.detachAppender(logged)
    }

    def "a #kind is read"() {
        when:
        def extracted = extractor.extract(name, content)

        then:
        extracted.mediaType() == type
        extracted.text().contains("Werkstatt")

        where:
        kind       | name            | content                                    | type
        "DOCX"     | "info.docx"     | docx("Die Werkstatt ist ab 15 Uhr offen.")     | "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "PDF"      | "info.pdf"      | pdf("Die Werkstatt ist ab 15 Uhr offen.")      | "application/pdf"
        "text"     | "info.txt"      | bytes("Die Werkstatt ist ab 15 Uhr offen.")    | "text/plain"
        "CSV"      | "preise.csv"    | bytes("Leistung;Preis\nWerkstatt;10")          | "text/csv"
        "Markdown" | "info.md"       | bytes("# Betrieb\n\nDie Werkstatt ist offen") | "text/markdown"
        "log"      | "server.log"    | bytes("08:00 Werkstatt geöffnet")            | "text/x-log"
        "HTML"     | "info.html"     | bytes("<html><body><p>Werkstatt</p></body></html>") | "text/html"
    }

    def "Markdown is read as written - its headings and the blank lines between its paragraphs stay"() {
        given:
        def markdown = "# Betrieb\n\nDie Werkstatt ist offen.\n\n- Montag\n- Dienstag"

        expect:
        extractor.extract("info.md", bytes(markdown)).text() == markdown
    }

    def "a #kind the content mistakes for a mail is read as the text its name says"() {
        when:
        def extracted = extractor.extract(name, bytes(content))

        then:
        extracted.mediaType() == type
        extracted.text().contains(found)

        and: "parsed as that type - a mail parser would take the first line for a header and leave it out"
        extracted.text().contains(firstLine)

        where:
        kind                          | name                  | content                                                                                                         | type         | found                    | firstLine
        // Tika's magic takes "Article" at the start for a Usenet message (message/news).
        "CSV opening with Article"    | "Price List 2026.csv" | "Article,Description,Unit,Price EUR,Delivery time\nMF-1001,Steel bracket 40x40 galvanised,piece,2.40,3 days" | "text/csv"   | "Steel bracket"          | "Article,Description"
        // A mail saved as text: "From:" / "Received:" look like message/rfc822.
        "text opening with From:"     | "mail.txt"            | "From: anna@example.com\nSubject: Werkstatt\n\nDie Werkstatt ist ab 15 Uhr offen."                             | "text/plain" | "Werkstatt ist ab 15 Uhr" | "From: anna@example.com"
        "text opening with Received:" | "mail.txt"            | "Received: from mail.example.com\nFrom: anna@example.com\n\nDie Werkstatt ist offen."                          | "text/plain" | "Werkstatt ist offen"     | "Received: from"
    }

    def "Tika counts the mail types as text - which the rule above rests on"() {
        given:
        def registry = org.apache.tika.mime.MediaTypeRegistry.defaultRegistry

        expect:
        registry.isSpecializationOf(org.apache.tika.mime.MediaType.parse(type), org.apache.tika.mime.MediaType.TEXT_PLAIN)

        where:
        type << ["message/news", "message/rfc822", "text/csv", "text/html", "text/markdown"]
    }

    def "a document with more text than its limit is refused - not held in memory whole"() {
        when:
        extractor.text("export.txt", "text/plain", bytes("x" * (DocumentTextExtractor.MAX_TEXT_CHARACTERS + 1)))

        then:
        def e = thrown(ServiceException)
        e.message == "The document has too much text - at most 20000000 characters"

        expect:
        extractor.text("export.txt", "text/plain", bytes("x" * 1000)).length() == 1000
    }

    def "a spreadsheet of a few kilobytes that unpacks into more than its limit is refused before it is parsed"() {
        given: "shared strings of 201 MB - a parser holds them in memory whole"
        def archive = new ByteArrayOutputStream()
        new ZipOutputStream(archive).withCloseable { zip ->
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"))
            byte[] megabyte = new byte[1024 * 1024]
            201.times { zip.write(megabyte) }
            zip.closeEntry()
        }

        when:
        extractor.text("mappe.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", archive.toByteArray())

        then:
        archive.size() < 1024 * 1024
        def e = thrown(ServiceException)
        e.code() == "document.partTooLarge"
        e.params() == [max: "200"]
    }

    def "a spreadsheet whose parts are modest each but unpack into more than their limit together is refused"() {
        given: "three parts of 180 MB - none above the limit of one part"
        def archive = new ByteArrayOutputStream()
        new ZipOutputStream(archive).withCloseable { zip ->
            byte[] megabyte = new byte[1024 * 1024]
            3.times { sheet ->
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet${sheet}.xml"))
                180.times { zip.write(megabyte) }
                zip.closeEntry()
            }
        }

        when:
        extractor.text("mappe.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", archive.toByteArray())

        then:
        archive.size() < 1024 * 1024
        def e = thrown(ServiceException)
        e.code() == "document.partTooLarge"
        e.params() == [max: "500"]
    }

    def "a spreadsheet of more parts than a real one has is refused before any is unpacked"() {
        given:
        def archive = new ByteArrayOutputStream()
        new ZipOutputStream(archive).withCloseable { zip ->
            (DocumentTextExtractor.MAX_PARTS + 1).times { part ->
                zip.putNextEntry(new ZipEntry("xl/media/part${part}.bin"))
                zip.closeEntry()
            }
        }

        when:
        extractor.text("mappe.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", archive.toByteArray())

        then:
        def e = thrown(ServiceException)
        e.code() == "document.tooManyParts"
        e.params() == [max: "10000"]
    }

    def "a #kind is refused with 415 - also under an allowed name"() {
        when:
        extractor.extract(name, content)

        then:
        def e = thrown(ServiceException)
        e.status() == HttpStatus.UNSUPPORTED_MEDIA_TYPE
        e.message.contains("allowed: PDF, DOCX")

        where:
        kind                 | name        | content
        "ZIP archive"        | "a.zip"     | zip()
        "ZIP named like PDF" | "a.pdf"     | zip()
        // junrar is excluded from the build (THIRD-PARTY.md): a RAR is recognised by Tika's own detection and refused.
        "RAR archive"        | "a.rar"     | rar()
        "RAR named like TXT" | "a.txt"     | rar()
        "PNG image"          | "scan.png"  | png()
        "PNG named like TXT" | "scan.txt"  | png()
        "PNG named like CSV" | "preise.csv" | png()
    }

    def "what a document carries inside (an attached archive) is not read - only the agreed types are, and only as a file of their own"() {
        when:
        def extracted = extractor.extract("info.pdf", pdfWithAttachment("Die Werkstatt ist offen.", zip("innen.txt", "Geheimarchiv")))

        then:
        extracted.text().contains("Werkstatt")
        !extracted.text().contains("Geheimarchiv")
    }

    def "an empty document is a 400"() {
        when:
        extractor.extract("leer.txt", new byte[0])

        then:
        def e = thrown(ServiceException)
        e.status() == HttpStatus.BAD_REQUEST
    }

    private static byte[] bytes(String text) {
        text.getBytes("UTF-8")
    }

    private static byte[] docx(String text) {
        def document = new XWPFDocument()
        document.createParagraph().createRun().setText(text)
        def out = new ByteArrayOutputStream()
        document.write(out)
        out.toByteArray()
    }

    private static byte[] zip(String name, String text) {
        def out = new ByteArrayOutputStream()
        new ZipOutputStream(out).withCloseable { zip ->
            zip.putNextEntry(new ZipEntry(name))
            zip.write(bytes(text))
            zip.closeEntry()
        }
        out.toByteArray()
    }

    private static byte[] pdfWithAttachment(String text, byte[] attachment) {
        def document = Loader.loadPDF(pdf(text))
        def file = new PDEmbeddedFile(document, new ByteArrayInputStream(attachment))
        file.setSubtype("application/zip")
        def spec = new PDComplexFileSpecification()
        spec.setFile("anhang.zip")
        spec.setEmbeddedFile(file)
        def names = new PDEmbeddedFilesNameTreeNode()
        names.setNames(["anhang.zip": spec])
        def dictionary = new PDDocumentNameDictionary(document.documentCatalog)
        dictionary.setEmbeddedFiles(names)
        document.documentCatalog.setNames(dictionary)
        def out = new ByteArrayOutputStream()
        document.save(out)
        document.close()
        out.toByteArray()
    }

    private static byte[] pdf(String text) {
        def document = new PDDocument()
        def page = new PDPage()
        document.addPage(page)
        new PDPageContentStream(document, page).withCloseable { stream ->
            stream.beginText()
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12)
            stream.newLineAtOffset(50, 700)
            stream.showText(text)
            stream.endText()
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
            zip.write(bytes("Die Werkstatt ist offen"))
            zip.closeEntry()
        }
        out.toByteArray()
    }

    /** The start of a RAR 5 archive - enough for the detection, which reads the signature. */
    private static byte[] rar() {
        def archive = new byte[72]
        byte[] signature = [0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00]
        System.arraycopy(signature, 0, archive, 0, signature.length)
        archive
    }

    def "a knowledge source leaves out what its name says cannot be read: #name"() {
        expect:
        DocumentTextExtractor.readableByName(name, imagesRead) == readable

        where:
        name               | imagesRead || readable
        "handbuch.pdf"     | false      || true
        "HANDBUCH.PDF"     | false      || true
        "angebot.docx"     | false      || true
        "notiz.txt"        | false      || true
        "server.log"       | false      || true
        "liesmich.text"    | false      || true
        "seite.htm"        | false      || true
        "seite.xhtml"      | false      || true
        "readme.markdown"  | false      || true
        "foto.jpg"         | false      || false
        "foto.jpg"         | true       || true
        "plan.PNG"         | true       || true
        "archiv.zip"       | true       || false
        "alt.doc"          | false      || false
        "einstellung.conf" | false      || false
        "ohne-endung"      | false      || false
        "punkt."           | false      || false
    }

    def "every allowed type has an extension; the refusal names them as before"() {
        expect:
        DocumentTextExtractor.TYPES.every { !it.extensions().isEmpty() }
        DocumentTextExtractor.ALLOWED.keySet() == DocumentTextExtractor.TYPES*.mediaType() as Set
        DocumentTextExtractor.ALLOWED_NAMES == "PDF, DOCX, ODT, RTF, TXT, Markdown, HTML, PPTX, XLSX, ODS, CSV"
    }

    /** A 1x1 PNG. */
    private static byte[] png() {
        Base64.decoder.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
    }
}
