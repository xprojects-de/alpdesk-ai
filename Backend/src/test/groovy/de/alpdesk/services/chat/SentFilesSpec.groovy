package de.alpdesk.services.chat

import de.alpdesk.services.common.ServiceException
import spock.lang.Specification

/** Files sent with a question: small ones go with it whole, larger ones as an overview the model reads with tools. */
class SentFilesSpec extends Specification {

    /** At most 300 characters together, 100 of them with the question. */
    SentFiles sentFiles = new SentFiles(300, 100)

    def "files that fit go with the question whole, each under its name"() {
        when:
        def prepared = sentFiles.prepare([new SentFile("a.txt", "Inhalt A"), new SentFile("b.json", '{"x":1}')], true)

        then:
        prepared.text() == '\n\n=== The file "a.txt" (sent with the question) ===\nInhalt A' +
                '\n\n=== The file "b.json" (sent with the question) ===\n{"x":1}'
        prepared.explored().isEmpty()
    }

    def "a file too large for the question comes as an overview - the others still go whole while they fit"() {
        given:
        def log = """2026-10-07 06:00:01 INFO  start
2026-10-07 06:00:02 WARN  temperature high
2026-10-07 06:00:03 ERROR spindle stopped
  at Machine.run(Machine.java:12)
2026-10-07 06:00:04 INFO  restart"""

        when:
        def prepared = sentFiles.prepare([new SentFile("machine.log", log), new SentFile("note.txt", "kurz")], true)

        then:
        prepared.text() == '\n\n=== The file "machine.log" (sent with the question, too large to go with it whole - ' +
                'read it with search_file and read_file_lines) ===\n' + log.length() + ' characters, 5 lines' +
                '\n\n=== The file "note.txt" (sent with the question) ===\nkurz'
        prepared.explored()*.name() == ["machine.log"]
        prepared.explored()[0].lines().size() == 5
    }

    def "the overview says only the file's size, whatever its content: #name"() {
        expect:
        SentFiles.overview(new SentFile(name, content)).endsWith("===\n" + content.length() + " characters, 2 lines")

        where:
        name          | content
        "daten.csv"   | "a;b\n1;2"
        "machine.log" | "2026-10-07 06:00:01 INFO  start\n2026-10-07 06:00:02 ERROR stopped"
    }

    def "a model that cannot call tools gets a large file cut at the budget, told so"() {
        when:
        def prepared = sentFiles.prepare([new SentFile("a.txt", "x" * 60), new SentFile("b.txt", "y" * 80)], false)

        then:
        prepared.text().endsWith('=== The file "b.txt" (sent with the question, too large: its first 40 of 80 ' +
                'characters - say that the rest could not be read) ===\n' + "y" * 40)
        prepared.explored().isEmpty()
    }

    def "files that cannot come with a question: #reason"() {
        when:
        sentFiles.check(files)

        then:
        def e = thrown(ServiceException)
        e.message.startsWith(message)

        where:
        reason          | files                                                               || message
        "too many"      | (1..21).collect { new SentFile("f${it}.txt", "x") }                  || "At most 20 files"
        "no name"       | [new SentFile(" ", "x")]                                            || "Every file needs a name"
        "a long name"   | [new SentFile("n" * 201, "x")]                                      || "Every file needs a name"
        "no content"    | [new SentFile("a.txt", null)]                                       || 'The file "a.txt" has no content'
        "a name twice"  | [new SentFile("app.log", "x"), new SentFile(" APP.log", "y")]       || 'Two files are named " APP.log"'
        "too much text" | [new SentFile("a.txt", "x" * 200), new SentFile("b.txt", "y" * 101)] || "The files may have at most 300 characters"
    }

    def "up to the limit they are accepted"() {
        when:
        sentFiles.check([new SentFile("a.txt", "x" * 300)])

        then:
        noExceptionThrown()
    }
}
