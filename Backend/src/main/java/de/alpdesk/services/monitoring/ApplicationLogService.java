package de.alpdesk.services.monitoring;

import de.alpdesk.dto.monitoring.ApplicationLogResponse;
import de.alpdesk.logging.RecentLogAppender;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Reads the application's recent warnings and errors from {@link RecentLogAppender}: the admin's view of the log
 * without SSH to the server, and the daily report's source for its log block. Nothing is stored - the buffer is the
 * appender's.
 */
@Singleton
public class ApplicationLogService {

    private static final String ERROR = "ERROR";

    private final RecentLogAppender appender = RecentLogAppender.installed();
    private final Instant startedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());

    /**
     * @param level  {@code ERROR} for errors only, {@code WARN} or null for everything the buffer holds
     * @param search a text every returned entry contains in its logger, message or stack trace (case-insensitive)
     */
    public ApplicationLogResponse recent(@Nullable String level, @Nullable String search) {
        boolean errorsOnly = errorsOnly(level);
        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        List<ApplicationLogResponse.Entry> entries = appender.entries().stream()
                .filter(entry -> !errorsOnly || ERROR.equals(entry.level()))
                .filter(entry -> needle == null || contains(entry, needle))
                .map(ApplicationLogResponse.Entry::from)
                .toList();
        return new ApplicationLogResponse(entries, appender.getMaxSize(), startedAt);
    }

    /** The kept entries since {@code since}, newest first - for the daily report. */
    public List<RecentLogAppender.Entry> since(Instant since) {
        return appender.entries().stream().filter(entry -> !entry.timestamp().isBefore(since)).toList();
    }

    /** The buffer is full and its oldest entry is younger than {@code since}: entries of the period were dropped. */
    public boolean lostSince(Instant since) {
        List<RecentLogAppender.Entry> entries = appender.entries();
        return entries.size() >= appender.getMaxSize() && entries.getLast().timestamp().isAfter(since);
    }

    public Instant startedAt() {
        return startedAt;
    }

    private static boolean errorsOnly(@Nullable String level) {
        if (level == null || level.isBlank() || "WARN".equalsIgnoreCase(level)) {
            return false;
        }
        if (ERROR.equalsIgnoreCase(level)) {
            return true;
        }
        throw ServiceException.badRequest("applicationLog.levelInvalid", "level must be WARN or ERROR");
    }

    private static boolean contains(RecentLogAppender.Entry entry, String needle) {
        return entry.logger().toLowerCase(Locale.ROOT).contains(needle)
                || entry.message().toLowerCase(Locale.ROOT).contains(needle)
                || (entry.stackTrace() != null && entry.stackTrace().toLowerCase(Locale.ROOT).contains(needle));
    }
}
