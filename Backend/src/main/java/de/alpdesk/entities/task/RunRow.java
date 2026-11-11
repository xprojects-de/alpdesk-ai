package de.alpdesk.entities.task;

import io.micronaut.core.annotation.Nullable;

/**
 * A row an extract step gave for one document - tenant database.
 *
 * @param values  the fields' values as JSON (an object by the fields' names)
 * @param source  the document's path and the checksum of its content - a changed document gives another
 * @param unclear why the row needs a person's look; null: clear
 */
public record RunRow(Long id, String stepId, String source, String values, @Nullable String unclear) {
}
