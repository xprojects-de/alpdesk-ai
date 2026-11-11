package de.alpdesk.services.tools;

import io.micronaut.core.annotation.Nullable;

/**
 * A tool call as the chat shows it to the user while the answer comes: the tool's name and the connected software it
 * belongs to (null for our own tools) - its own names, not the one the model sees ("Buchhaltung__find_invoice"). Shown,
 * never stored.
 */
public record ToolStep(String name, @Nullable String software) {
}
