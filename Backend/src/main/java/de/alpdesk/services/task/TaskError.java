package de.alpdesk.services.task;

import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

/**
 * A mistake in a task's text, where it is (line and column from 1; 0 when it concerns the whole task) - the code is
 * {@code task.<rule>}, translated by the frontend like every backend error, the message English.
 */
@Serdeable
public record TaskError(int line, int column, String code, Map<String, String> params, String message) {
}
