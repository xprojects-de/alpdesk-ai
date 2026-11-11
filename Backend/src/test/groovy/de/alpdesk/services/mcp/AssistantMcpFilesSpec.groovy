package de.alpdesk.services.mcp

import de.alpdesk.services.chat.ChatService
import spock.lang.Specification

/** ask_assistant's result with the answer's files: text as text, the rest as base64 - no empty text part. */
class AssistantMcpFilesSpec extends Specification {

    def "the answer's text comes first, then its files: #reason"() {
        when:
        def result = AssistantMcpTools.withFiles(text, files)

        then:
        result.content()*.type() == types

        where:
        reason                          | text      | files                    || types
        "text and a file"               | "Fertig." | [file("liste.md", true)] || ["text", "resource"]
        "only a file - no empty text"   | ""        | [file("liste.md", true)] || ["resource"]
        "no text, no file - one part"   | ""        | []                       || ["text"]
        "a file left out for its size"  | ""        | [left("gross.zip")]      || ["text"]
    }

    def "a text file goes as text, any other as base64; one left out is named"() {
        when:
        def result = AssistantMcpTools.withFiles("Fertig.", [file("liste.md", true), file("export.zip", false), left("gross.zip")])

        then:
        result.content()[0].text() == 'Fertig.\n[file "gross.zip" left out - the files of one answer may have at most 8 MB together]'
        result.content()[1].resource().text() == "liste.md"
        result.content()[2].resource().blob() == "export.zip".bytes.encodeBase64().toString()
    }

    private static ChatService.DeliveredFile file(String name, boolean text) {
        new ChatService.DeliveredFile(name, text ? "text/markdown" : "application/zip", name.bytes, text ? name : null)
    }

    private static ChatService.DeliveredFile left(String name) {
        new ChatService.DeliveredFile(name, "application/zip", null, null)
    }
}
