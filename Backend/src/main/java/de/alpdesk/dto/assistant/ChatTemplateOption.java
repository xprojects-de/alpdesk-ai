package de.alpdesk.dto.assistant;

import de.alpdesk.entities.assistant.ChatTemplate;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

/** A template as the users and the client software choose it - its name and what it is for, not its instructions. */
@Serdeable
@Schema(description = "A template of the tenant's assistant: its own instructions and settings for a kind of question")
public record ChatTemplateOption(
        Long id,
        @Schema(example = "Nachschlagen")
        String name,
        @Schema(description = "What the template is for; null: none written",
                example = "Checks source code against the company's rules - send the files with the question")
        @Nullable String description
) {
    public static ChatTemplateOption from(ChatTemplate template) {
        return new ChatTemplateOption(template.id(), template.name(), template.description());
    }
}
