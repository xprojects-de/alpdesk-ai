package de.alpdesk.dto.document;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/**
 * A knowledge source with its last run: lastError null - it worked; lastSucceededAt - the last run that listed the
 * whole tree; openFiles and openBytes - new and changed files not read yet; running - a run is under way now.
 */
@Serdeable
public record KnowledgeSourceResponse(
        Long id,
        String name,
        Long fileStoreId,
        String fileStore,
        String folder,
        Long areaId,
        String area,
        String pattern,
        String schedule,
        boolean active,
        Instant nextRunAt,
        boolean running,
        Instant lastStartedAt,
        Instant lastFinishedAt,
        String lastError,
        int lastAdded,
        int lastChanged,
        int lastDeleted,
        int lastRefused,
        int lastSkipped,
        Instant lastSucceededAt,
        int openFiles,
        long openBytes
) {
}
