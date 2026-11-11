package de.alpdesk.dto.document;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A knowledge source. folder: relative to the store's folder, left out: the store's folder itself; areaId left out: the
 * company knowledge; pattern left out: every file; schedule left out: DAILY; active left out: on create true, on
 * update unchanged.
 */
@Serdeable
public record KnowledgeSourceRequest(
        String name,
        Long fileStoreId,
        @Nullable String folder,
        @Nullable Long areaId,
        /** File names with * and ?, e.g. "*.pdf". */
        @Nullable String pattern,
        /** MANUAL, HOURLY, EVERY_3_HOURS, EVERY_6_HOURS, EVERY_12_HOURS or DAILY. */
        @Nullable String schedule,
        @Nullable Boolean active
) {
}
