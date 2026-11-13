package de.alpdesk.entities.assistant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** A template the tenant's users choose in the chat: instructions of its own, settings of its own (null: the tenant's). */
@MappedEntity("chat_template")
public record ChatTemplate(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        String name,

        String instructions,

        /** What it is for, said to whoever chooses it from outside (A2A, MCP, /api/v1) - not to the model; null: none. */
        @Nullable
        String description,

        @Nullable
        AnswerSource answerSource,

        @Nullable
        Creativity creativity,

        @Nullable
        Reasoning reasoning,

        @Nullable
        Integer searchResults,

        Instant createdAt,

        Instant updatedAt
) {
}
