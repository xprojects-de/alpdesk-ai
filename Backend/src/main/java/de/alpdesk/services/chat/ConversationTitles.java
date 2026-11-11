package de.alpdesk.services.chat;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.assistant.Creativity;
import de.alpdesk.entities.assistant.Reasoning;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.services.ai.AiModels;
import de.alpdesk.services.ai.ChatOptions;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.ai.UsageService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * A short title for a new conversation, written by its chat model from the first question
 * (docs/architecture/ai-features.md "Chat") - like the big chat apps name theirs. Asked in the background while the
 * answer is written, so it costs no waiting; when it fails, the conversation keeps the question's first line.
 */
@Singleton
public class ConversationTitles {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationTitles.class);

    static final int MAX_LENGTH = 80;
    /** Enough of a long question to tell its subject. */
    private static final int MAX_QUESTION = 2000;
    private static final String INSTRUCTIONS = """
            You name conversations. Answer with a short title for the conversation that starts with the user's message:
            at most six words, in the language of the message, no quotes, no full stop - only the title.""";
    /** A title needs neither ideas nor thinking - the cheapest call the model allows. */
    private static final ChatOptions OPTIONS = new ChatOptions(Creativity.LOW, Reasoning.OFF);

    private final AiModels aiModels;
    private final UsageService usage;
    private final ExecutorService executor;

    public ConversationTitles(AiModels aiModels, UsageService usage, @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.aiModels = aiModels;
        this.usage = usage;
        this.executor = executor;
    }

    /** The title, asked in the background; completes with null when the model fails or answers nothing usable. */
    public CompletableFuture<String> suggest(Tenant tenant, @Nullable Long userId, AiModel model, ResolvedKey key,
                                             String question) {
        return CompletableFuture.supplyAsync(() -> ask(tenant, userId, model, key, question), executor)
                .exceptionally(failure -> {
                    LOG.debug("No title for a conversation of tenant {}: {}", tenant.id(), failure.toString());
                    return null;
                });
    }

    private String ask(Tenant tenant, Long userId, AiModel model, ResolvedKey key, String question) {
        String text = question.length() > MAX_QUESTION ? question.substring(0, MAX_QUESTION) : question;
        ChatResponse response = aiModels.chat(model, key.apiKey(),
                List.of(SystemMessage.from(INSTRUCTIONS), UserMessage.from(text)), List.of(), OPTIONS);
        usage.record(tenant.id(), userId, model, key.source(), response.tokenUsage(), 0);
        return clean(response.aiMessage().text());
    }

    /**
     * The title as the model wrote it, without what models like to add: quotes, Markdown marks, a "Titel:" before it,
     * a full stop after it. Null when nothing is left.
     */
    static String clean(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String title = text.strip().lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("");
        title = title.replaceAll("^[\"'„“”«»*#\\s]+|[\"'„“”«»*.:;,\\s]+$", "")
                .replaceFirst("(?i)^(titel|title)\\s*:\\s*", "")
                .replaceAll("^[\"'„“”«»*\\s]+", "")
                .strip();
        if (title.length() > MAX_LENGTH) {
            title = title.substring(0, MAX_LENGTH - 1).strip() + "…";
        }
        return title.isEmpty() ? null : title;
    }
}
