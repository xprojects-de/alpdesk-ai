package de.alpdesk.dto.mcp;

import io.micronaut.serde.annotation.Serdeable;

/** An argument of a prompt: the user fills it in the chat's form. */
@Serdeable
public record McpPromptArgument(String name, String description, boolean required) {
}
