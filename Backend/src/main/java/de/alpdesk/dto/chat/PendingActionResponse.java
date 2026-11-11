package de.alpdesk.dto.chat;

import de.alpdesk.entities.chat.PendingAction;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/** A changing tool call of a connected software the answer stopped at - the user runs or cancels it. */
@Serdeable
@Schema(description = "A changing tool call of the company's software, waiting for the user")
public record PendingActionResponse(
        Long id,
        @Schema(description = "The connected software's name")
        String software,
        @Schema(description = "The software's tool")
        String tool,
        @Schema(description = "The arguments the model chose")
        Map<String, Object> arguments,
        @Schema(description = "PENDING, RUN, CANCELLED or EXPIRED")
        String status,
        @Schema(description = "What the software answered when it was run")
        @Nullable String result,
        Instant createdAt
) {
    public static PendingActionResponse from(PendingAction action) {
        return new PendingActionResponse(action.id(), action.softwareName(), action.toolName(),
                arguments(action.arguments()), action.status().name(), action.result(), action.createdAt());
    }

    /** The model's arguments as an object; a call whose arguments are no object shows none. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(String json) {
        try {
            Map<String, Object> map = Json.MAPPER.readValue(json, Map.class);
            return map == null ? Map.of() : map;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private static final class Json {
        static final JsonMapper MAPPER = JsonMapper.createDefault();
    }
}
