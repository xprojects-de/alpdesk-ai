package de.alpdesk.entities.chat;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;

/** A row of a tenant database's table "message". */
public record Message(
        Long id,
        Long conversationId,
        MessageRole role,
        String content,
        /** The model that answered (its display name then); null for the user's messages. */
        @Nullable String modelName,
        Instant createdAt,
        /** An answer that is the tenant's A2A agent's question back - it waits for the user's answer. */
        boolean waiting
) {
}
