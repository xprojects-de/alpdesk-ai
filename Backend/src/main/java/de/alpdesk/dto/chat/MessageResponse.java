package de.alpdesk.dto.chat;

import de.alpdesk.entities.chat.ConversationFile;
import de.alpdesk.entities.chat.Message;
import de.alpdesk.entities.chat.PendingAction;
import de.alpdesk.services.chat.ChatService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Serdeable
@Schema(description = "A message of a conversation")
public record MessageResponse(
        @Schema(description = "null for an answer stored nowhere (an API key that keeps no history)")
        @Nullable Long id,
        @Schema(description = "USER (the question) or ASSISTANT (the answer)")
        String role,
        String content,
        @Schema(description = "The model that answered; null for a question")
        String modelName,
        @Schema(description = "The files the answer created for download")
        List<ChatFileResponse> files,
        Instant createdAt,
        @Schema(description = "The changing tool call of the company's software the answer stopped at; null for none")
        @Nullable PendingActionResponse action,
        @Schema(description = "The answer is the tenant's A2A agent's question back: the next question answers it")
        boolean waiting
) {
    public static MessageResponse from(Message message, List<ConversationFile> files, @Nullable PendingAction action) {
        return new MessageResponse(message.id(), message.role().name(), message.content(), message.modelName(),
                files.stream().map(ChatFileResponse::from).toList(), message.createdAt(),
                action == null ? null : PendingActionResponse.from(action), message.waiting());
    }

    public static MessageResponse from(ChatService.MessageWithFiles message) {
        return from(message.message(), message.files(), message.action());
    }
}
