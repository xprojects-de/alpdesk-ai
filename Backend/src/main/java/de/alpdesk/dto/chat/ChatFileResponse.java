package de.alpdesk.dto.chat;

import de.alpdesk.entities.chat.ConversationFile;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Serdeable
@Schema(description = "A file of a conversation - uploaded, or created by the assistant for download")
public record ChatFileResponse(
        Long id,
        @Schema(description = "UPLOADED or GENERATED (created by the assistant)")
        String origin,
        String fileName,
        @Schema(description = "The type recognised from the content, or the type of a created file")
        String contentType,
        long sizeBytes,
        @Schema(description = "How many images of the file the assistant is sent (an image, the pages of a scanned PDF)")
        int imageCount,
        Instant createdAt,
        @Schema(description = "Deleted after the installation's retention time (a video after hours, any other file after "
                + "days): listed, no longer downloadable or read by the assistant")
        boolean expired
) {
    public static ChatFileResponse from(ConversationFile file) {
        return new ChatFileResponse(file.id(), file.origin().name(), file.fileName(), file.contentType(),
                file.sizeBytes(), file.imageCount(), file.createdAt(), file.isExpired());
    }
}
