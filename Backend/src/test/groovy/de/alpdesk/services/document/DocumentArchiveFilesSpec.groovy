package de.alpdesk.services.document

import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** How an archive is read entry by entry: the names' code page, entries that cannot be unpacked, and its limits. */
class DocumentArchiveFilesSpec extends Specification {

    Path zip = Files.createTempFile("alpdesk-archive", ".zip")

    def cleanup() {
        Files.deleteIfExists(zip)
    }

    def "names Windows' own ZIP tools write in the old code page are read - umlauts included"() {
        given: "no UTF-8 flag, the names in IBM437 - what the Explorer of Windows 10 writes"
        write(Charset.forName("IBM437"), ["Übersicht.txt": "Text", "Prüfungen/Maße.txt": "Text"])

        expect:
        read() == ["Übersicht.txt", "Prüfungen/Maße.txt"]
    }

    def "UTF-8 names are read as they are"() {
        given:
        write(StandardCharsets.UTF_8, ["Übersicht.txt": "Text"])

        expect:
        read() == ["Übersicht.txt"]
    }

    def "an entry that cannot be unpacked is skipped and reported - the others are read"() {
        given: "the first entry's data damaged, its listing intact"
        // Numbers compress little: the damaged bytes lie well inside the entry's data.
        write(StandardCharsets.UTF_8, ["kaputt.txt": (1..400).join(","), "gut.txt": "Gut"])
        damageFirstEntry()

        when:
        def skipped = [:]
        def files = []
        DocumentArchive.read(zip, new DocumentArchive.Handler() {
            void file(String path, byte[] content) { files << path }

            void skipped(String path, String reason) { skipped[path] = reason }
        })

        then:
        files == ["gut.txt"]
        skipped == ["kaputt.txt": "could not be unpacked"]
    }

    def "an archive beyond its limits is refused: #limit"() {
        given: "limits of 2 entries, 10 bytes each and 15 together - a document archive's and a website's work alike"
        write(StandardCharsets.UTF_8, entries)

        when:
        DocumentArchive.read(zip, new DocumentArchive.Limits(2, 10, 15), new DocumentArchive.Handler() {
            void file(String path, byte[] content) {}

            void skipped(String path, String reason) {}
        })

        then:
        def e = thrown(de.alpdesk.services.common.ServiceException)
        e.code == code

        where:
        limit                                       | entries                                      | code
        "more entries than allowed"                 | ["a.txt": "1", "b.txt": "2", "c.txt": "3"]   | "archive.tooManyEntries"
        "unpacked larger, counted while it is read" | ["a.txt": "x" * 10, "b.txt": "y" * 10]       | "archive.tooLarge"
    }

    private void write(Charset names, Map<String, String> entries) {
        new ZipOutputStream(Files.newOutputStream(zip), names).withCloseable { out ->
            entries.each { name, text ->
                out.putNextEntry(new ZipEntry(name))
                out.write(text.getBytes(StandardCharsets.UTF_8))
                out.closeEntry()
            }
        }
    }

    private List<String> read() {
        def paths = []
        DocumentArchive.read(zip, new DocumentArchive.Handler() {
            void file(String path, byte[] content) { paths << path }

            void skipped(String path, String reason) { throw new AssertionError("skipped " + path + ": " + reason) }
        })
        paths
    }

    /** Overwrites the middle of the first entry's compressed data - its CRC no longer matches. */
    private void damageFirstEntry() {
        byte[] bytes = Files.readAllBytes(zip)
        def header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        int dataStart = 30 + header.getShort(26) + header.getShort(28)
        (0..7).each { bytes[dataStart + 4 + it] = (byte) 0x5A }
        Files.write(zip, bytes)
    }
}
