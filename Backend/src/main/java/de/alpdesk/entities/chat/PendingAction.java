package de.alpdesk.entities.chat;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;

/** A row of a tenant database's table "pending_action": a changing tool call waiting for the user. */
public record PendingAction(
        Long id,
        Long conversationId,
        /** The answer that asked for it. */
        Long messageId,
        Long modelId,
        Long connectionId,
        Long toolId,
        String softwareName,
        String toolName,
        String callId,
        String calledName,
        /** The model's arguments, a JSON object. */
        String arguments,
        PendingActionStatus status,
        @Nullable String result,
        Instant createdAt,
        @Nullable Instant decidedAt
) {
}
