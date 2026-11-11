package de.alpdesk.entities.document;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/**
 * A folder of one of the tenant's file stores synced into its knowledge (docs/architecture/knowledge-sources.md):
 * management database. What it saw file by file lives in the tenant database (source_file), its documents there too.
 */
@MappedEntity("knowledge_source")
public record KnowledgeSource(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        String name,

        Long fileStoreId,

        /** Relative to the store's folder; "" is the store's folder itself. */
        String folder,

        /** Where new documents go; null: the company knowledge. */
        @Nullable
        Long areaId,

        /** File names with * and ?; null: every file. */
        @Nullable
        String pattern,

        SourceSchedule schedule,

        /** Switched off: no scheduled run; a run by hand still works. */
        boolean active,

        /** When its schedule is due next; null for MANUAL or switched off. */
        @Nullable
        Instant nextRunAt,

        @Nullable
        Instant lastStartedAt,

        @Nullable
        Instant lastFinishedAt,

        /** The last run's error code; null: it worked. */
        @Nullable
        String lastError,

        int lastAdded,

        int lastChanged,

        int lastDeleted,

        int lastRefused,

        /** Files left out by their name's extension - a type that is not read. */
        int lastSkipped,

        /** The last run that listed the whole tree. */
        @Nullable
        Instant lastSucceededAt,

        /** New and changed files not read yet. */
        int openFiles,

        long openBytes,

        Instant createdAt,

        Instant updatedAt
) {
}
