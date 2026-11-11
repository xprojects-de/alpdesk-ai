package de.alpdesk.dto.chat;

import de.alpdesk.services.mcp.McpConnectionService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A skill of the tenant's A2A agent a question may leave out (docs/architecture/a2a.md "The skills' choice") - listed
 * instead of the connected software when the tenant answers through its agent.
 */
@Serdeable
@Schema(description = "A skill of the tenant's A2A agent a question may leave out (excludedSkillIds: the id without "
        + "\"skill:\")")
public record SkillChoiceResponse(
        @Schema(description = "\"skill:\" and the skill's id", example = "skill:invoices")
        String id,
        String name,
        @Nullable
        @Schema(description = "The skill's description")
        String instructions
) implements ChoiceResponse {
    public static final String PREFIX = "skill:";

    public static SkillChoiceResponse from(McpConnectionService.Skill skill) {
        return new SkillChoiceResponse(PREFIX + skill.id(), skill.name(), skill.description());
    }
}
