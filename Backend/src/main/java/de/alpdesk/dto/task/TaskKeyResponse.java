package de.alpdesk.dto.task;

import io.micronaut.serde.annotation.Serdeable;

/** A key the task is assigned to - kind "API" (the client software's) or "MCP" - shown with the task. */
@Serdeable
public record TaskKeyResponse(String kind, String name) {
}
