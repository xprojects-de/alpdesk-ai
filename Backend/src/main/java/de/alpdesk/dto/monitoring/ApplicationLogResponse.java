package de.alpdesk.dto.monitoring;

import de.alpdesk.logging.RecentLogAppender;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;

/** The application's recent warnings and errors (the admin only), newest first. */
@Serdeable
public record ApplicationLogResponse(
        List<Entry> entries,
        /** How many events the buffer keeps - older ones are only in the container log. */
        int capacity,
        /** When the application started: the buffer holds nothing from before. */
        Instant startedAt
) {

    @Serdeable
    public record Entry(Instant timestamp, String level, String logger, String thread, String message,
                        @Nullable String stackTrace) {

        public static Entry from(RecentLogAppender.Entry entry) {
            return new Entry(entry.timestamp(), entry.level(), entry.logger(), entry.thread(), entry.message(),
                    entry.stackTrace());
        }
    }
}
