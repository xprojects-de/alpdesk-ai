package de.alpdesk.dto.chat;

import de.alpdesk.services.chat.ChatFileService;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Serdeable
@Schema(description = "Files added to a conversation - a new one when none was given")
public record ChatUploadResponse(ConversationResponse conversation, List<ChatFileResponse> files) {
    public static ChatUploadResponse from(ChatFileService.UploadResult result) {
        return new ChatUploadResponse(ConversationResponse.from(result.conversation()),
                result.files().stream().map(ChatFileResponse::from).toList());
    }
}
