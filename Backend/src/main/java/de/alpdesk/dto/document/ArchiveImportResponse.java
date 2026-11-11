package de.alpdesk.dto.document;

import de.alpdesk.services.document.DocumentService;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** What importing a ZIP archive did, file by file in the archive's order. */
@Serdeable
public record ArchiveImportResponse(List<ImportedFile> files) {

    /**
     * outcome: CREATED, REPLACED, UNCHANGED, DUPLICATE (detail: the path of the document with the same content) or
     * SKIPPED (detail: why).
     */
    @Serdeable
    public record ImportedFile(String path, String outcome, Long documentId, String detail) {
    }

    public static ArchiveImportResponse from(DocumentService.ArchiveImport archive) {
        return new ArchiveImportResponse(archive.files().stream()
                .map(file -> new ImportedFile(file.path(), file.outcome().name(), file.documentId(), file.detail()))
                .toList());
    }
}
