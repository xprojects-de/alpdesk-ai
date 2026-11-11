package de.alpdesk.entities.assistant;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** How a tenant's assistant answers - its instructions and the defaults a template may override. */
@MappedEntity("tenant_assistant")
public record TenantAssistant(

        @Id
        Long tenantId,

        /** Sent with every question of the tenant. */
        String instructions,

        AnswerSource answerSource,

        Creativity creativity,

        Reasoning reasoning,

        /** The passages one search returns. */
        int searchResults,

        /** The chat model of the questions that choose none - those of the MCP server; null: none chosen. */
        @Nullable
        Long defaultModelId,

        /** The image model of the chat; null: the only one the tenant may use (AssistantService.imageModel). */
        @Nullable
        Long imageModelId,

        Instant updatedAt
) {
}
