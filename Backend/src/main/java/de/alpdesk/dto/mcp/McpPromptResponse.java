package de.alpdesk.dto.mcp;

import de.alpdesk.entities.mcp.McpPrompt;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import io.micronaut.serde.annotation.Serdeable;

import java.io.IOException;
import java.util.List;

/** A prompt of a connected software with its arguments, as the server gave them. */
@Serdeable
public record McpPromptResponse(Long id, String name, @Nullable String title, String description,
                                List<McpPromptArgument> arguments, boolean enabled, boolean offered) {

    public static McpPromptResponse from(McpPrompt prompt, JsonMapper json) {
        return new McpPromptResponse(prompt.id(), prompt.name(), prompt.title(), prompt.description(),
                arguments(prompt.arguments(), json), prompt.enabled(), prompt.offered());
    }

    /** The stored arguments; none when they cannot be read - the prompt is still listed. */
    public static List<McpPromptArgument> arguments(String stored, JsonMapper json) {
        try {
            return json.readValue(stored, Argument.listOf(McpPromptArgument.class));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
