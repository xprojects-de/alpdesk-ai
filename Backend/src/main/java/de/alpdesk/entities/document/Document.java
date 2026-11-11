package de.alpdesk.entities.document;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;

/** A row of a tenant database's table "document" - without its text, which is read only to embed it. */
public record Document(
        Long id,
        String fileName,
        /** The folder it came from in a ZIP archive or a knowledge source ("Handbuch/Kapitel"); "" for a single upload. */
        String folder,
        @Nullable String contentType,
        long sizeBytes,
        /** Whether the image model still has to read its images or scanned pages before it is embedded. */
        boolean readsImages,
        DocumentStatus status,
        @Nullable String error,
        int chunkCount,
        @Nullable Long uploadedBy,
        DocumentOrigin origin,
        /** The client software's own key for it; null for an upload in the frontend or a push without one. */
        @Nullable String externalId,
        /**
         * SHA-256 of the extracted text, of the file itself when the image model reads it - an unchanged document is not
         * embedded again.
         */
        @Nullable String contentHash,
        /** The knowledge area it belongs to (the management database's knowledge_area.id); null: the company knowledge. */
        @Nullable Long areaId,
        /** The knowledge source keeping it up to date (the management database's knowledge_source.id); null for others. */
        @Nullable Long sourceId,
        Instant createdAt,
        Instant updatedAt
) {
    /** Folder and file name - how the chat names it as a source. */
    public String path() {
        return folder.isEmpty() ? fileName : folder + "/" + fileName;
    }
}
