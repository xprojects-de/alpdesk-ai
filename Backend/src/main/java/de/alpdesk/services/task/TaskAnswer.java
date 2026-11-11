package de.alpdesk.services.task;

import io.micronaut.core.annotation.Nullable;

/** What a prompt step answered in one round of a run, and the word it picked from its choices. */
public record TaskAnswer(String step, int round, @Nullable String choice, String text) {
}
