package de.alpdesk.dto.api.v1;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

@Serdeable
@Schema(description = "A chat model the client software can ask")
public record ApiModelResponse(
        Long id,
        // type: without it micronaut-openapi reads each allowed value as JSON and warns "Can't convert ANTHROPIC".
        @Schema(description = "The model's provider", type = "string", allowableValues = {"ANTHROPIC", "OPENAI", "GEMINI",
                "OLLAMA", "OPENAI_COMPATIBLE", "BEDROCK"})
        String provider,
        String displayName,
        @Schema(description = "Whether the tenant has an API key for the model's provider - without one, asking it answers 409")
        boolean usable
) {
}
