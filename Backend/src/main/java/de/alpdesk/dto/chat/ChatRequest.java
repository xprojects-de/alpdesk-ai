package de.alpdesk.dto.chat;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Serdeable
@Schema(description = "A question to the tenant's assistant")
public record ChatRequest(
        @Nullable
        @Schema(description = "The conversation to continue; left out starts a new one. Not with an API key that keeps "
                + "no history")
        Long conversationId,
        @Nullable
        @Schema(description = "An active chat model (GET /api/v1/models); may be left out with an API key that keeps no "
                + "history - the tenant's default model answers")
        Long modelId,
        @Nullable
        @Schema(description = "The question, up to 20000 characters. May be left out with a templateId: the template's "
                + "name is then the question - for a template that is the task itself", example = "Wie lange gilt die Garantie?")
        String message,
        @Nullable
        @Schema(description = "A template of the tenant's assistant (GET /api/v1/templates); left out: the tenant's settings")
        Long templateId,
        @Nullable
        @Schema(description = "Only with an API key that keeps no history: text files read for this answer and kept "
                + "nowhere (at most 20, e.g. log files). Small ones go with the question whole; the assistant searches "
                + "and reads larger ones itself - send only what it should look at")
        List<SentFileRequest> files,
        @Nullable
        @Schema(description = "Connected software this question leaves out (ids of GET /api/v1/software) - it only narrows "
                + "what the key may use. Left out: the conversation's last choice (none for a new one); empty: every one "
                + "again. Kept with the conversation and returned with it")
        List<Long> excludedConnectionIds,
        @Nullable
        @Schema(description = "For a tenant answering through its A2A agent: the agent's skills this question leaves out "
                + "(the ids of GET /api/v1/software without \"skill:\"). Left out: the conversation's last choice; empty: "
                + "every skill again")
        List<String> excludedSkillIds
) {
}
