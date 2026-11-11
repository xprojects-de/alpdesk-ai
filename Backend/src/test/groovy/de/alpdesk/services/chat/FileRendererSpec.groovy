package de.alpdesk.services.chat

import com.sun.net.httpserver.HttpServer
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import spock.lang.Specification

import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.nio.file.Files

/** The files the model creates: Markdown becomes DOCX and PDF, CSV becomes XLSX, the text formats stay as written. */
class FileRendererSpec extends Specification {

    static final String MARKDOWN = """# Wartungsplan 2027

Die **Wartung** beginnt um *7 Uhr* - für alle Geräte.

- Filter tauschen
- Öl prüfen

| Teil     | Preis |
|----------|-------|
| Filter   | 10 €  |
"""

    FileRenderer renderer = new FileRenderer()

    def "a DOCX has the headings, paragraphs, list items and the table of the Markdown"() {
        when:
        def document = new XWPFDocument(new ByteArrayInputStream(renderer.render(OutputFormat.DOCX, MARKDOWN).content()))
        def paragraphs = document.paragraphs*.text.findAll { it }

        then:
        paragraphs[0] == "Wartungsplan 2027"
        document.paragraphs[0].runs[0].bold
        paragraphs[1] == "Die Wartung beginnt um 7 Uhr - für alle Geräte."
        paragraphs.containsAll(["• Filter tauschen", "• Öl prüfen"])
        document.tables.size() == 1
        document.tables[0].rows*.tableCells*.text == [["Teil", "Preis"], ["Filter", "10 €"]]
    }

    def "a PDF holds the text with umlauts and the table"() {
        when:
        def pdf = renderer.render(OutputFormat.PDF, MARKDOWN)
        def text = Loader.loadPDF(pdf.content()).withCloseable { new PDFTextStripper().getText(it) }

        then:
        pdf.contentType() == "application/pdf"
        text.contains("Wartungsplan 2027")
        text.contains("für alle Geräte")
        text.contains("Filter")
    }

    def "an XLSX has the CSV's rows, numbers as numbers, the first row as header"() {
        when:
        def csv = 'Artikel;Preis;Hinweis\nSchraube;80;"verzinkt; Größe M8"\nMutter;120.5;"sagt ""Hallo"""'
        def sheet = new XSSFWorkbook(new ByteArrayInputStream(renderer.render(OutputFormat.XLSX, csv).content())).getSheetAt(0)

        then:
        sheet.getRow(0).getCell(0).stringCellValue == "Artikel"
        sheet.getRow(1).getCell(1).numericCellValue == 80
        sheet.getRow(1).getCell(2).stringCellValue == "verzinkt; Größe M8"
        sheet.getRow(2).getCell(1).numericCellValue == 120.5
        sheet.getRow(2).getCell(2).stringCellValue == 'sagt "Hallo"'
    }

    def "an XLSX cell '#value' is #kind - a leading zero stays text"() {
        when:
        def cell = new XSSFWorkbook(new ByteArrayInputStream(renderer.render(OutputFormat.XLSX, "Wert\n" + value).content()))
                .getSheetAt(0).getRow(1).getCell(0)

        then:
        (cell.cellType == org.apache.poi.ss.usermodel.CellType.NUMERIC) == number

        where:
        value              || number
        "01067"            || false
        "000123"           || false
        "0049891234"       || false
        "1234567890123456" || false
        "0"                || true
        "0.5"              || true
        "-12.75"           || true
        "1067"             || true
        kind = number ? "a number" : "text"
    }

    def "an XLSX Excel cannot hold is refused with a reason for the model: #reason"() {
        when:
        renderer.render(OutputFormat.XLSX, csv)

        then:
        def refused = thrown(de.alpdesk.services.common.ServiceException)
        refused.code() == "chatFile.xlsxNotCreated"

        where:
        reason                | csv
        "a cell too long"     | "Text\n" + "x" * 40_000
        "too many columns"    | (1..16_400).collect { "c" + it }.join(";")
    }

    def "the delimiter is the one the first line has most of"() {
        expect:
        FileRenderer.parseCsv(csv) == rows

        where:
        csv               | rows
        "a,b\n1,2"        | [["a", "b"], ["1", "2"]]
        "a;b\n1;2"        | [["a", "b"], ["1", "2"]]
        "a\tb\r\n1\t2"    | [["a", "b"], ["1", "2"]]
    }

    def "text formats are stored as written - CSV with a byte order mark for Excel, HTML as a whole document"() {
        expect:
        new String(renderer.render(OutputFormat.MD, "# Titel").content(), "UTF-8") == "# Titel"
        renderer.render(OutputFormat.CSV, "a;b").content()[0..2] == [(byte) 0xEF, (byte) 0xBB, (byte) 0xBF]
        new String(renderer.render(OutputFormat.HTML, "<p>Hallo</p>").content(), "UTF-8").startsWith("<!DOCTYPE html>")
        new String(renderer.render(OutputFormat.HTML, "<html><body>x</body></html>").content(), "UTF-8") == "<html><body>x</body></html>"
    }

    def "a PDF loads nothing from outside the text - no internal address, no local file"() {
        given: "a server inside the network and an image on the server's disk"
        def requests = 0
        def server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests++
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        def internal = "http://127.0.0.1:${server.address.port}"
        def image = Files.createTempFile("alpdesk-local", ".png")
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", image.toFile())

        and: "what a prompt injection could make the model write"
        def markdown = """# Bericht

![a](${internal}/latest/meta-data)

<img src="${internal}/raw.png">

<link rel="stylesheet" href="${internal}/style.css">

![b](${image.toUri()})
"""

        when:
        def pdf = renderer.render(OutputFormat.PDF, markdown)
        def images = Loader.loadPDF(pdf.content()).withCloseable { document ->
            document.pages.collect { page -> page.resources.XObjectNames.findAll { page.resources.isImageXObject(it) } }.flatten()
        }
        def text = Loader.loadPDF(pdf.content()).withCloseable { new PDFTextStripper().getText(it) }

        then: "the PDF is made - without fetching or embedding anything"
        text.contains("Bericht")
        requests == 0
        images.isEmpty()

        cleanup:
        server?.stop(0)
        Files.deleteIfExists(image)
    }
}
