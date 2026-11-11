package de.alpdesk.services.chat;

import de.alpdesk.entities.chat.Conversation;
import de.alpdesk.entities.chat.ConversationFile;
import de.alpdesk.entities.chat.Message;
import de.alpdesk.entities.chat.PendingAction;
import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * A question and its answer, stored in the conversation, with the documents the answer drew on, the files it created
 * and the changing tool call it stopped at (null: none). question is null for an answer that went on after the user
 * decided such a call.
 */
public record ChatResult(Conversation conversation, @Nullable Message question, Message answer, List<String> sources,
                         List<ConversationFile> files, @Nullable PendingAction action) {
}
