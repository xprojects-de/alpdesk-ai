package de.alpdesk.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import spock.lang.Specification

/** The in-memory buffer itself: what of an event is kept, the cap on the stack trace, and rolling over. */
class RecentLogAppenderSpec extends Specification {

    def context = new LoggerContext()
    def appender = new RecentLogAppender(maxSize: 3)

    def setup() {
        appender.context = context
        appender.start()
    }

    def "keeps time, level, logger, thread, the formatted message and the stack trace, newest first"() {
        when:
        appender.doAppend(event(Level.WARN, "first {}", "one"))
        appender.doAppend(event(Level.ERROR, "second", new IllegalStateException("boom")))

        then:
        def entries = appender.entries()
        entries*.message == ["second", "first one"]
        entries*.level == ["ERROR", "WARN"]
        entries*.logger == ["de.alpdesk.Test", "de.alpdesk.Test"]
        entries*.thread == [Thread.currentThread().name] * 2
        entries[0].timestamp != null
        entries[0].stackTrace.startsWith("java.lang.IllegalStateException: boom")
        entries[0].stackTrace.contains("RecentLogAppenderSpec")
        entries[1].stackTrace == null
    }

    def "a long stack trace is cut"() {
        given: "a chain of causes longer than the cap"
        Throwable cause = new RuntimeException("root")
        40.times { cause = new RuntimeException("level $it", cause) }

        when:
        appender.doAppend(event(Level.ERROR, "deep", cause))

        then:
        def trace = appender.entries()[0].stackTrace
        trace.length() == RecentLogAppender.MAX_STACK_TRACE_LENGTH + 1
        trace.endsWith("…")
    }

    def "the oldest entries fall out beyond maxSize"() {
        when:
        (1..5).each { appender.doAppend(event(Level.WARN, "message $it")) }

        then:
        appender.entries()*.message == ["message 5", "message 4", "message 3"]
    }

    /** An event as Logback builds it: the last argument, if a Throwable, is the event's exception. */
    private LoggingEvent event(Level level, String message, Object... args) {
        def logger = context.getLogger("de.alpdesk.Test")
        def throwable = args.length > 0 && args[-1] instanceof Throwable ? (Throwable) args[-1] : null
        def messageArgs = throwable == null ? args : args.take(args.length - 1) as Object[]
        return new LoggingEvent("de.alpdesk.Test", logger, level, message, throwable, messageArgs)
    }
}
