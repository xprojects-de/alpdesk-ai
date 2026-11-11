package de.alpdesk.dto.docsite;

import de.alpdesk.services.docsite.DocSiteService;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Map;

/** The website as stored, and the ZIP's files that are not part of it, with why. */
@Serdeable
public record DocSiteUploadResponse(DocSiteResponse site, List<SkippedFile> skipped) {

    public static DocSiteUploadResponse from(DocSiteService.Upload upload, Map<Long, String> areaNames) {
        return new DocSiteUploadResponse(DocSiteResponse.from(upload.site(), areaNames),
                upload.skipped().stream().map(file -> new SkippedFile(file.path(), file.reason())).toList());
    }

    @Serdeable
    public record SkippedFile(String path, String reason) {
    }
}
