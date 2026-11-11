package de.alpdesk.dto.api.v1;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Serdeable
@Schema(description = "The tenant the calling API key belongs to")
public record TenantSelfResponse(
        Long id,
        String name,
        @Schema(description = "The knowledge areas the key finds and pushes documents into, besides the company knowledge")
        List<String> areas,
        @Schema(description = "The tenant answers every question through its A2A agent: no model or template to "
                + "choose (a templateId is refused), the agent's skills are the choice (GET /api/v1/software)")
        boolean answersThroughAgent
) {
}
