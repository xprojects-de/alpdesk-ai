package de.alpdesk.services.chat;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.chat.Conversation;
import de.alpdesk.entities.chat.ConversationOwner;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.tools.ToolContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import de.alpdesk.services.a2a.AgentAnswers;
import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A checked question, ready to be sent: the messages for the model (instructions, history, the question with the list
 * of the conversation's files), the key, the tools it gets and their context, and where the answer goes.
 */
public record PreparedChat(
        Tenant tenant,
        /** Whose conversation it is: the asking user's, or one API key's of the client software. */
        ConversationOwner owner,
        /** The conversation it continues; null starts a new one once the answer is there. */
        @Nullable Conversation conversation,
        AiModel model,
        ResolvedKey key,
        /** The tenant's settings for this question - with its template's, if one was chosen. */
        AssistantService.Assistant assistant,
        /** null: no new question - the model goes on after the user decided a changing tool call. */
        @Nullable String question,
        List<ChatMessage> messages,
        List<ToolSpecification> toolSpecifications,
        /** What the tools of this answer share and leave: created files, documents found. */
        ToolContext tools,
        /** The title being written for a conversation's first question (ConversationTitles); null for a later one. */
        @Nullable CompletableFuture<String> title,
        /** The changing call the user ran before the model goes on; null for a question or a cancelled call. */
        @Nullable ConfirmedRun confirmedRun,
        /**
         * The question for the tenant's A2A agent when the tenant answers through it (docs/architecture/a2a.md) - then
         * model, key, assistant and tools are null: no model of ours answers.
         */
        @Nullable AgentAnswers.Question agent
) {
    /** The answer that stopped at the call, and the files the software returned when it ran. */
    public record ConfirmedRun(Long stoppedAnswerId, List<ChatFileService.PendingFile> files) {
    }

    /** Stops an agent's answer (the stream was closed: a stop, a logout) - the agent loop's goes on and is stored. */
    public void stop() {
        if (agent != null) {
            agent.call().stop();
        }
    }

    /** The asking user - who caused the usage; null for the client software and the MCP server. */
    public @Nullable Long userId() {
        return owner.userId();
    }
}
