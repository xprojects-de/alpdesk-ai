package de.alpdesk.services.chat

import spock.lang.Specification

/** The files ask_assistant sends with an answer: up to 8 MB together, the ones beyond named without their content. */
class DeliveredFilesSpec extends Specification {

    static final long MB = 1024 * 1024

    def "files beyond 8 MB together come without content - a smaller one after them still goes"() {
        given:
        def created = [pending("export.zip", "application/zip", 7 * MB, null),
                       pending("bericht.pdf", "application/pdf", 2 * MB, null),
                       pending("README.md", "text/markdown", 1024, "# Projekt")]

        when:
        def delivered = ChatService.delivered(created) { file -> file.fileName().bytes }

        then:
        delivered*.name() == ["export.zip", "bericht.pdf", "README.md"]
        delivered*.content().collect { it == null ? null : new String(it) } == ["export.zip", null, "README.md"]
        delivered*.text() == [null, null, "README.md"]
    }

    def "a text file goes as text only when its content is UTF-8 - a Latin-1 log goes as bytes"() {
        when:
        def delivered = ChatService.delivered([pending("server.log", "text/plain", 20, "Größe")]) { file -> content }

        then:
        delivered[0].text() == text

        where:
        content                                      || text
        "Größe überschritten".getBytes("UTF-8")      || "Größe überschritten"
        "Größe überschritten".getBytes("ISO-8859-1") || null
    }

    private static ChatFileService.PendingFile pending(String name, String type, long size, String text) {
        new ChatFileService.PendingFile(name, type, size, "tenants/1/chat/x/", text, 0)
    }
}
