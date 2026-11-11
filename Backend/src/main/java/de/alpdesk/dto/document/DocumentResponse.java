package de.alpdesk.dto.document;

import de.alpdesk.entities.document.Document;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Serdeable
@Schema(description = "A document of the tenant's knowledge base")
public record DocumentResponse(
        Long id,
        String fileName,
        @Schema(description = "The folder it came from in a ZIP archive imported in the frontend; empty otherwise")
        String folder,
        @Schema(description = "The type recognised from the content", example = "application/pdf")
        String contentType,
        long sizeBytes,
        @Schema(description = "PENDING, PROCESSING, READY (the chat finds it) or FAILED (see error)")
        String status,
        @Schema(description = "Why embedding failed (status FAILED)")
        String error,
        @Schema(description = "Into how many chunks it was split")
        int chunkCount,
        @Schema(description = "UPLOAD (in the frontend), API (pushed by the client software) or SOURCE (a file store's folder "
                + "synced by a knowledge source)")
        String origin,
        @Schema(description = "The client software's own key for the document, if it sent one", example = "manual-4711")
        String externalId,
        @Schema(description = "The id of the knowledge area it belongs to; empty for the company knowledge everyone finds")
        Long areaId,
        @Schema(description = "The name of the knowledge area it belongs to; empty for the company knowledge everyone finds", example = "Vertrieb")
        String area,
        @Schema(description = "The knowledge source keeping it up to date (origin SOURCE); empty for others")
        Long sourceId,
        Instant createdAt,
        Instant updatedAt
) {
    /** areaNames: the tenant's areas by id. */
    public static DocumentResponse from(Document document, Map<Long, String> areaNames) {
        return new DocumentResponse(document.id(), document.fileName(), document.folder(), document.contentType(),
                document.sizeBytes(),
                document.status().name(), document.error(), document.chunkCount(), document.origin().name(), document.externalId(),
                document.areaId(), document.areaId() == null ? null : areaNames.get(document.areaId()),
                document.sourceId(), document.createdAt(), document.updatedAt());
    }
}
