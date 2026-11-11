package de.alpdesk.dto.docsite;

import de.alpdesk.entities.docsite.DocSite;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.Map;

/** A handbook's website; areaId and areaName null: the whole tenant's. */
@Serdeable
public record DocSiteResponse(Long id, @Nullable Long areaId, @Nullable String areaName, int fileCount, long sizeBytes,
                              Instant uploadedAt) {

    public static DocSiteResponse from(DocSite site, Map<Long, String> areaNames) {
        return new DocSiteResponse(site.id(), site.areaId(), site.areaId() == null ? null : areaNames.get(site.areaId()),
                site.fileCount(), site.sizeBytes(), site.uploadedAt());
    }
}
