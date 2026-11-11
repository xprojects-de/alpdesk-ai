package de.alpdesk.dto.assistant;

import de.alpdesk.entities.assistant.AnswerSource;
import de.alpdesk.entities.assistant.Creativity;
import de.alpdesk.entities.assistant.Reasoning;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** A template - a setting left out (null) takes the tenant's. */
@Serdeable
public record ChatTemplateRequest(
        String name,
        @Nullable String instructions,
        @Nullable AnswerSource answerSource,
        @Nullable Creativity creativity,
        @Nullable Reasoning reasoning,
        @Nullable Integer searchResults
) {
}
