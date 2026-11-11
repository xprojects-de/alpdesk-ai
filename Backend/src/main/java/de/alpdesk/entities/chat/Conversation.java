package de.alpdesk.entities.chat;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.util.List;

/** A row of a tenant database's table "conversation": one user's chat, or the client software's (userId null). */
public record Conversation(
        Long id,
        @Nullable Long userId,
        String title,
        /** The template of its last question; null for the tenant's settings alone. */
        @Nullable Long templateId,
        Instant createdAt,
        Instant updatedAt,
        /**
         * The connected software its questions leave out (the user's choice in the chat, roadmap point 51), as stored -
         * a connection deleted or no longer reached since is dropped where it is read; empty: none.
         */
        List<Long> excludedConnectionIds,
        /** The A2A agent's skills its questions leave out (docs/architecture/a2a.md); empty: none. */
        List<String> excludedSkillIds
) {
}
