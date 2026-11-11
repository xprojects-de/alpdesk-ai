package de.alpdesk.services.agent;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.services.ai.ChatOptions;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.tools.ToolContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * What the agent loop needs for one answer: the model with its key and options, the messages so far (instructions,
 * history, the question), the tools offered and what they share, and how many rounds it may take.
 */
public record AgentRun(
        Tenant tenant,
        /** Who caused the usage; null for the client software and the MCP server. */
        @Nullable Long userId,
        AiModel model,
        ResolvedKey key,
        ChatOptions options,
        List<ChatMessage> messages,
        List<ToolSpecification> tools,
        ToolContext context,
        /** Rounds of the answer: the model may call tools in all but the last, which must answer in text. */
        int maxRounds
) {
}
