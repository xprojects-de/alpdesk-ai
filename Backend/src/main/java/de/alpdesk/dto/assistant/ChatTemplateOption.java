package de.alpdesk.dto.assistant;

import de.alpdesk.entities.assistant.ChatTemplate;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

/** A template as the users and the client software choose it - its name only, not its instructions. */
@Serdeable
@Schema(description = "A template of the tenant's assistant: its own instructions and settings for a kind of question")
public record ChatTemplateOption(
        Long id,
        @Schema(example = "Nachschlagen")
        String name
) {
    public static ChatTemplateOption from(ChatTemplate template) {
        return new ChatTemplateOption(template.id(), template.name());
    }
}
