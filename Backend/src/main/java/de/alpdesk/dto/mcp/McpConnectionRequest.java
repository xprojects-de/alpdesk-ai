package de.alpdesk.dto.mcp;

import de.alpdesk.entities.mcp.McpConnectionProtocol;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** A connection to the tenant's software. The key left out: unchanged - another host than before drops it. */
@Serdeable
public record McpConnectionRequest(
        String name,
        /** The software's MCP endpoint, e.g. "https://software.example.com/mcp". */
        String baseUrl,
        @Nullable String apiKey,
        /** True removes the stored key. */
        @Nullable Boolean removeApiKey,
        /** MCP or A2A - left out: MCP on create, unchanged on update; it cannot change once the connection is made. */
        @Nullable McpConnectionProtocol protocol,
        /** An A2A agent answering every question of the chat - left out: off on create, unchanged on update. */
        @Nullable Boolean answersChat,
        /**
         * A client id from the portal of a server that signs in with OAuth and registers no client itself - with its
         * secret, if the portal gave one; only on update, for a connection whose server asks for a sign-in.
         */
        @Nullable String clientId,
        @Nullable String clientSecret
) {
}
