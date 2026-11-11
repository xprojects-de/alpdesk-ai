package de.alpdesk.dto.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** A prompt of a connected software a user may use: the software's name, the prompt and its arguments. */
@Serdeable
public record SoftwarePromptResponse(Long id, String software, String name, @Nullable String title,
                                     String description, List<McpPromptArgument> arguments) {
}
