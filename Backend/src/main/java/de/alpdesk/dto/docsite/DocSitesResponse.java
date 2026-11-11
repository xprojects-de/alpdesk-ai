package de.alpdesk.dto.docsite;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** A tenant's handbook websites for its admins, and the largest ZIP an upload may have. */
@Serdeable
public record DocSitesResponse(List<DocSiteResponse> sites, long maxZipBytes) {
}
