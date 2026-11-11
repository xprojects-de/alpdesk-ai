package de.alpdesk.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import io.micronaut.core.annotation.Nullable;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Keeps the most recent log events in memory, so the daily report can mail them - without SSH to the server.
 * Configured in {@code logback.xml} as appender {@value #NAME} with a threshold filter (WARN and above - INFO lines of
 * every request and embedding would roll the buffer over within hours) and {@code maxSize}.
 * <p>
 * Each event is kept as a flat {@link Entry}, not as the event itself: an event holds its arguments (entities,
 * requests) and its MDC, which must not stay alive for days. The stack trace is capped at {@value #MAX_STACK_TRACE_LENGTH}
 * characters. The buffer is bounded and in memory only: a restart empties it, and the container log keeps the
 * whole history.
 */
public class RecentLogAppender extends AppenderBase<ILoggingEvent> {

    public static final String NAME = "RECENT";
    static final int MAX_STACK_TRACE_LENGTH = 4000;

    /** One kept log event. */
    public record Entry(Instant timestamp, String level, String logger, String thread, String message,
                        @Nullable String stackTrace) {
    }

    private int maxSize = 1000;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    /**
     * The appender of this class on the root logger. Found by type, not by its name {@value #NAME}: the AOT jar
     * replaces logback.xml with generated code ({@code replaceLogbackXml}), which builds the appender with its filter
     * and {@code maxSize} but does not name it.
     */
    public static RecentLogAppender installed() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Iterator<Appender<ILoggingEvent>> appenders = root.iteratorForAppenders();
        while (appenders.hasNext()) {
            if (appenders.next() instanceof RecentLogAppender appender) {
                return appender;
            }
        }
        throw new IllegalStateException("The logging configuration has no appender of " + RecentLogAppender.class.getName());
    }

    /** Set from logback.xml ({@code <maxSize>}). */
    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    @Override
    protected void append(ILoggingEvent event) {
        Entry entry = new Entry(Instant.ofEpochMilli(event.getTimeStamp()), event.getLevel().toString(),
                event.getLoggerName(), event.getThreadName(), event.getFormattedMessage(),
                stackTrace(event.getThrowableProxy()));
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > maxSize) {
                entries.removeFirst();
            }
        }
    }

    /** The kept events, newest first. */
    public List<Entry> entries() {
        List<Entry> snapshot;
        synchronized (entries) {
            snapshot = new ArrayList<>(entries);
        }
        return snapshot.reversed();
    }

    @Nullable
    private static String stackTrace(@Nullable IThrowableProxy throwable) {
        if (throwable == null) {
            return null;
        }
        String trace = ThrowableProxyUtil.asString(throwable);
        return trace.length() <= MAX_STACK_TRACE_LENGTH ? trace : trace.substring(0, MAX_STACK_TRACE_LENGTH) + "…";
    }
}
