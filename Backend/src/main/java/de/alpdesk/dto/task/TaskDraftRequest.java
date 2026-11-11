package de.alpdesk.dto.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * What a draft is to do, in the tenant admin's own words.
 *
 * @param source  the task's current text when it is to be changed; null: a new task
 * @param modelId the chat model to write it; null: the assistant's default model
 */
@Serdeable
public record TaskDraftRequest(@Nullable String description, @Nullable String source, @Nullable Long modelId) {
}
