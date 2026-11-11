package de.alpdesk.dto.chat;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** The event "sources" of the chat stream: the documents the answer draws on so far, sent when a search finds new ones. */
@Serdeable
public record ChatStreamSources(List<String> sources) {
}
