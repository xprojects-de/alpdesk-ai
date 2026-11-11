package de.alpdesk.dto.chat;

import de.alpdesk.entities.chat.MessageRole;
import de.alpdesk.services.chat.ChatResult;
import de.alpdesk.services.chat.ChatService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Serdeable
@Schema(description = "The answer, stored with the question in the conversation - or stored nowhere with an API key "
        + "that keeps no history")
public record ChatResponse(
        @Schema(description = "The conversation; null with an API key that keeps no history")
        @Nullable ConversationResponse conversation,
        @Schema(description = "The question; null for an answer that went on after a decided tool call, and with an "
                + "API key that keeps no history")
        @Nullable MessageResponse question,
        MessageResponse answer,
        @Schema(description = "The file names of the tenant's documents the answer drew on")
        List<String> sources
) {
    public static ChatResponse from(ChatResult result) {
        return new ChatResponse(ConversationResponse.from(result.conversation()),
                result.question() == null ? null : MessageResponse.from(result.question(), List.of(), null),
                MessageResponse.from(result.answer(), result.files(), result.action()), result.sources());
    }

    /** An answer stored nowhere: no conversation, and the answer has no id. */
    public static ChatResponse from(ChatService.Answer answer) {
        return new ChatResponse(null, null, new MessageResponse(null, MessageRole.ASSISTANT.name(), answer.text(),
                answer.modelName(), List.of(), Instant.now(), null, false), answer.sources());
    }
}
