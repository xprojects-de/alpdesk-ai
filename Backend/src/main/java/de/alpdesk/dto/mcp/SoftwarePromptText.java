package de.alpdesk.dto.mcp;

import io.micronaut.serde.annotation.Serdeable;

/** The text the software made of a prompt - it goes into the question's input field. */
@Serdeable
public record SoftwarePromptText(String text) {
}
