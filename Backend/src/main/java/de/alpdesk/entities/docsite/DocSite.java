package de.alpdesk.entities.docsite;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;

/**
 * A handbook's website: the static website uploaded for the whole tenant (areaId null) or one knowledge area, read by
 * people beside the chat - never by the assistant. Tenant database; its files are in the file storage below
 * storagePrefix.
 */
public record DocSite(Long id, @Nullable Long areaId, String storagePrefix, int fileCount, long sizeBytes,
                      Instant uploadedAt) {
}
