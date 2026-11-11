package de.alpdesk.dto.chat;

import io.micronaut.serde.annotation.Serdeable;

/** An event "delta" of the chat stream: the next piece of the answer's text. */
@Serdeable
public record ChatStreamPart(String text) {
}
