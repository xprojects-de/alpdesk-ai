package de.alpdesk.dto.mcp;

import de.alpdesk.dto.oauth.GrantView;
import de.alpdesk.entities.mcp.McpConnectionProtocol;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;

/**
 * A connection with its tools and prompts. toolsError / promptsError: why reading its tools or its prompts failed just
 * now (after creating or reloading it); null otherwise. The key itself never goes out, only its hint - nor a token of
 * its sign-in.
 */
@Serdeable
public record McpConnectionResponse(
        Long id,
        String name,
        String baseUrl,
        String keyHint,
        Instant toolsLoadedAt,
        String toolsError,
        String promptsError,
        /**
         * MCP keys with it get its reading tools, prompts and instructions directly ("Direkt an MCP-Clients"); false:
         * through ask_assistant only. Which keys may use it is the keys' choice.
         */
        boolean passesThrough,
        /** The instructions its server gave, cut to 2,000 characters; null: none. */
        String instructions,
        /** MCP or A2A; an A2A agent's tools are its card's skills, it has no prompts. */
        McpConnectionProtocol protocol,
        /** The A2A agent answers every question of the tenant's chat. */
        boolean answersChat,
        List<McpToolResponse> tools,
        List<McpPromptResponse> prompts,
        /** The sign-in at the software's server when it signs in with OAuth; null: it asks for none (or a key). */
        @Nullable GrantView signIn
) {
}
