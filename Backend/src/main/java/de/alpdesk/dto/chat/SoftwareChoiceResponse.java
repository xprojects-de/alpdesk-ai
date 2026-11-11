package de.alpdesk.dto.chat;

import de.alpdesk.entities.mcp.McpConnection;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

@Serdeable
@Schema(description = "A connected software a question may leave out - its name and what its server says about itself")
public record SoftwareChoiceResponse(
        Long id,
        String name,
        @Nullable
        @Schema(description = "What the software's server said in its initialize (up to 2,000 characters); empty: nothing")
        String instructions
) implements ChoiceResponse {
    public static SoftwareChoiceResponse from(McpConnection connection) {
        return new SoftwareChoiceResponse(connection.id(), connection.name(), connection.instructions());
    }
}
