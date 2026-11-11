package de.alpdesk.services.task.step

import de.alpdesk.services.filestore.NamePattern
import spock.lang.Specification
import spock.lang.Timeout

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * A read_files step's pattern (ReadFilesStep.patterns, NamePattern.matches): * any text, ? one character, a date placeholder
 * written as each of the last days - matched without a regular expression, so a pattern of many * cannot hold a task
 * thread on a name it does not match.
 */
class ReadFilesPatternSpec extends Specification {

    def "a name matches its pattern: * any text, ? one character, everything else literally"() {
        expect:
        NamePattern.matches(pattern, name) == matches

        where:
        pattern            | name                    || matches
        "*.log"            | "app.log"               || true
        "*.log"            | "app.log.1"             || false
        "app-?.log"        | "app-1.log"             || true
        "app-?.log"        | "app-12.log"            || false
        "*_*.log"          | "2026-10-24_server.log" || true
        "*server*"         | "2026-10-24_server.log" || true
        "app.log"          | "app.log"               || true
        "app.log"          | "App.log"               || false
        "a.c"              | "abc"                   || false
        "*"                | ""                      || true
        "**x**"            | "x"                     || true
        "*a*b"             | "aXbYb"                 || true
        "*a*b"             | "aXbYc"                 || false
        "[0-9]*.log"       | "[0-9]a.log"            || true
        "[0-9]*.log"       | "1a.log"                || false
    }

    @Timeout(5)
    def "a pattern of many * gives its answer at once on names it does not match"() {
        given: "patterns a regular expression of .* backtracks on for minutes"
        def names = (1..200).collect { "server-" + "a" * 30 + it }

        expect:
        names.every { !NamePattern.matches("*" * 30 + "x.log", it) }
        names.every { !NamePattern.matches("*a" * 20 + "*b", it) }
    }

    def "a date placeholder is written as each of the last days, in the installation's time zone"() {
        given:
        def clock = Clock.fixed(Instant.parse("2026-10-24T22:30:00Z"), ZoneId.of("UTC"))
        def step = new ReadFilesStep(null, null, ZoneId.of("Europe/Vienna"), clock)

        expect:
        step.patterns("{yyyy-MM-dd}_*.log", 2) as List == ["2026-10-25_*.log", "2026-10-24_*.log"]
        step.patterns("app.log", 3) as List == ["app.log"]
    }
}
