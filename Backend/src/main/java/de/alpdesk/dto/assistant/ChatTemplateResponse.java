package de.alpdesk.dto.assistant;

import de.alpdesk.entities.assistant.AnswerSource;
import de.alpdesk.entities.assistant.ChatTemplate;
import de.alpdesk.entities.assistant.Creativity;
import de.alpdesk.entities.assistant.Reasoning;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record ChatTemplateResponse(
        Long id,
        String name,
        String instructions,
        @Nullable String description,
        AnswerSource answerSource,
        Creativity creativity,
        Reasoning reasoning,
        Integer searchResults,
        Instant createdAt,
        Instant updatedAt
) {
    public static ChatTemplateResponse from(ChatTemplate template) {
        return new ChatTemplateResponse(template.id(), template.name(), template.instructions(), template.description(),
                template.answerSource(), template.creativity(), template.reasoning(), template.searchResults(),
                template.createdAt(), template.updatedAt());
    }
}
