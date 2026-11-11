package de.alpdesk.dto.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

/** The values the user gave a prompt's arguments, by the argument's name. */
@Serdeable
public record SoftwarePromptRequest(@Nullable Map<String, String> arguments) {
}
