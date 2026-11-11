package de.alpdesk.dto.chat;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

@Serdeable
@Schema(description = "A text file sent with the question (e.g. a log file) - read for this answer only, never stored")
public record SentFileRequest(
        @Schema(description = "The file's name, 1 to 200 characters", example = "machine-7.log",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Nullable String name,
        @Schema(description = "The file's text", requiredMode = Schema.RequiredMode.REQUIRED)
        @Nullable String content
) {
}
