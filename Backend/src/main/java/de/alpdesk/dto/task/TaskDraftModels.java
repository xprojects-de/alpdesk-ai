package de.alpdesk.dto.task;

import de.alpdesk.dto.assistant.ModelOption;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** The chat models a draft may use, and the one preselected (the assistant's default model; null: none). */
@Serdeable
public record TaskDraftModels(List<ModelOption> models, @Nullable Long defaultModelId) {
}
