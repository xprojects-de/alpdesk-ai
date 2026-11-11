package de.alpdesk.dto.chat;

import de.alpdesk.entities.chat.Conversation;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Serdeable
@Schema(description = "A conversation")
public record ConversationResponse(
        Long id,
        @Schema(description = "The first line of its first question")
        String title,
        @Schema(description = "The template of its last question; empty for the tenant's settings alone")
        Long templateId,
        Instant createdAt,
        Instant updatedAt,
        @Schema(description = "The connected software its questions leave out - the last choice sent with a question")
        List<Long> excludedConnectionIds,
        @Schema(description = "The skills of the tenant's A2A agent its questions leave out - the last choice sent")
        List<String> excludedSkillIds
) {
    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(conversation.id(), conversation.title(), conversation.templateId(),
                conversation.createdAt(), conversation.updatedAt(), conversation.excludedConnectionIds(),
                conversation.excludedSkillIds());
    }
}
