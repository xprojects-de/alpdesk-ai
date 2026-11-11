package de.alpdesk.entities.chat;

import io.micronaut.core.annotation.Nullable;

/**
 * Whose conversations they are: a tenant user's, or the client software's of one API key (user id null). Every
 * conversation, its messages and files are only ever read together with their owner - another user's or another key's
 * answers may quote knowledge areas the asker does not find.
 */
public record ConversationOwner(@Nullable Long userId, @Nullable Long apiKeyId) {

    public static ConversationOwner user(Long userId) {
        return new ConversationOwner(userId, null);
    }

    public static ConversationOwner apiKey(Long apiKeyId) {
        return new ConversationOwner(null, apiKeyId);
    }
}
