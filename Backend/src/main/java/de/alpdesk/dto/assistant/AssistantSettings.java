package de.alpdesk.dto.assistant;

import de.alpdesk.entities.assistant.AnswerSource;
import de.alpdesk.entities.assistant.Creativity;
import de.alpdesk.entities.assistant.Reasoning;
import de.alpdesk.entities.assistant.TenantAssistant;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** A tenant's assistant settings - read and written whole. */
@Serdeable
public record AssistantSettings(
        /** Sent with every question; blank: none. */
        @Nullable String instructions,
        AnswerSource answerSource,
        Creativity creativity,
        Reasoning reasoning,
        /** 3 to 20. */
        Integer searchResults,
        /** The chat model of the questions that choose none - those of the MCP server; null: none. */
        @Nullable Long defaultModelId,
        /** The image model the chat edits images with; null: the only one the tenant may use, else none. */
        @Nullable Long imageModelId
) {
    public static AssistantSettings from(TenantAssistant assistant) {
        return new AssistantSettings(assistant.instructions(), assistant.answerSource(), assistant.creativity(),
                assistant.reasoning(), assistant.searchResults(), assistant.defaultModelId(),
                assistant.imageModelId());
    }
}
