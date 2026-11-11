package de.alpdesk.dto.chat;

import de.alpdesk.services.tools.ToolStep;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** An event "tool" of the chat stream: a tool about to run - its name and its connected software (null for ours). */
@Serdeable
public record ChatStreamTool(String name, @Nullable String software) {

    public static ChatStreamTool from(ToolStep step) {
        return new ChatStreamTool(step.name(), step.software());
    }
}
