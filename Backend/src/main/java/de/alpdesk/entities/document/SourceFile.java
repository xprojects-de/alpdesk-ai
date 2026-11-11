package de.alpdesk.entities.document;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/**
 * A file a knowledge source saw - a row of a tenant database's table "source_file": its path relative to the source's
 * folder, its size and time as the store listed them, and its document, or why there is none.
 */
public record SourceFile(
        Long sourceId,
        String path,
        long size,
        @Nullable Instant modified,
        @Nullable Long documentId,
        /**
         * Why the file was refused (a document.* code) or could not be read (a fileStore.* code); null with documentId
         * null: its document was deleted by hand.
         */
        @Nullable String error
) {
    /**
     * The store could not read it (no right to it, locked) - open again at every run, but after the other open files:
     * files that stay unreadable must not take a scheduled run's budget from the new ones.
     */
    public boolean unreadable() {
        return error != null && error.startsWith("fileStore.");
    }

    /** Its document was deleted by hand - the file is left alone until it changes. */
    public boolean deletedByHand() {
        return documentId == null && error == null;
    }

    /**
     * Refused because the tenant had no image model - open again once it has one. "No text" counts only for a PDF (a
     * scan): another file without text is refused the same with an image model and is not read again.
     */
    public boolean refusedWithoutImageModel() {
        return "document.imageNeedsScanModel".equals(error)
                || "document.noText".equals(error) && path.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /**
     * Whether the store lists it as it was when it was last read (or refused): not read again. Times to the
     * millisecond - the database keeps microseconds, a clock may give more.
     */
    public boolean unchanged(long size, @Nullable Instant modified) {
        return this.size == size && Objects.equals(millis(this.modified), millis(modified));
    }

    private static @Nullable Instant millis(@Nullable Instant time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.MILLIS);
    }
}
