package de.alpdesk.services.agent;

import de.alpdesk.services.ai.AiModels;
import de.alpdesk.services.ai.CallTokens;
import de.alpdesk.services.ai.UsageService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.mcp.McpTools;
import de.alpdesk.services.tools.ToolContext;
import de.alpdesk.services.tools.ToolResult;
import de.alpdesk.services.tools.ToolStep;
import de.alpdesk.services.tools.Toolbox;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The agent loop (decided 6 October 2026, our own - docs/architecture/ai-features.md "What of langchain4j is used"):
 * asks the model, runs the tools it calls, gives it their results and asks again, until it answers in text or the
 * last round is reached, which must answer without tools - the round before it is told so -; a model that ends
 * without a word is asked once more.
 * Every round is a provider call recorded in the usage.
 * <p>
 * It answers either in one piece ({@link #run}) or streamed ({@link #stream}); what is asked and what becomes of the
 * answer is the caller's. No transaction is held - a provider can take minutes.
 */
@Singleton
public class AgentLoop {

    private static final Logger LOG = LoggerFactory.getLogger(AgentLoop.class);

    /**
     * Asks a model once more that ended without a word (docs/architecture/ai-features.md "An answer without text"): from
     * what it found, without tools, and saying so when that does not answer the question.
     */
    public static final String ASK_AGAIN = "You wrote no answer. Answer the question now, in text, from what you have "
            + "found. If what you found does not answer it, say that you found nothing about it in the documents searched.";
    /**
     * Told before the last round in which tools can be called: the model did not know its rounds and worked on until
     * the last round took its tools away - stills drawn but never handed out, and then no word (Claude Sonnet 5.5 on a
     * video, 18 October 2026).
     */
    public static final String LAST_TOOL_ROUND = "This is your last round in which you can call tools - hand out or "
            + "create now what you have ready. In the next round you answer in text, without tools.";
    /** Opens the message with the files the tools returned. */
    public static final String TOOL_FILES = "The files the tools returned:";
    /**
     * A line that is nothing but a note the chat's history puts on an earlier answer (ChatService.toChatMessage) -
     * never part of an answer.
     */
    private static final Pattern HISTORY_NOTE =
            Pattern.compile("(?m)^[ \\t]*\\[(?:Created with a tool: [^\\n]*|Stopped at the call [^\\n]*|No text)][ \\t]*$\\R?");

    private final AiModels aiModels;
    private final Toolbox toolbox;
    private final UsageService usage;

    public AgentLoop(AiModels aiModels, Toolbox toolbox, UsageService usage) {
        this.aiModels = aiModels;
        this.toolbox = toolbox;
        this.usage = usage;
    }

    /**
     * Where a streamed answer goes: its parts, the model's reasoning, the documents found, each tool call before it
     * runs, the answer or one error.
     */
    public record Listener(Consumer<String> onPart, Consumer<String> onThinking, Consumer<List<String>> onSources,
                           Consumer<ToolStep> onTool, Consumer<String> onAnswer, Consumer<ServiceException> onError) {
    }

    /**
     * The answer in one piece (/api/v1/chat, the MCP server): streamed from the provider like the chat's answer and
     * collected here - one path for every asker, so the stream's protections hold everywhere (AiModels.StreamStop: a
     * stream fallen silent, blanks running away, reasoning in a loop), which the provider's call in one piece has none
     * of (decided 13 October 2026). The wait has no limit of its own: StreamStop ends every round. 502 when the
     * provider fails or gives no answer, also when asked again.
     */
    public String run(AgentRun run) {
        CompletableFuture<String> answer = new CompletableFuture<>();
        stream(run, new Listener(part -> { }, thinking -> { }, sources -> { }, tool -> { }, answer::complete,
                answer::completeExceptionally));
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw notCompleted();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ServiceException failure) {
                throw failure;
            }
            throw new IllegalStateException("The answer failed", e.getCause());
        }
    }

    /**
     * Streams the answer: its parts as they come, the documents found whenever a search adds some, then the whole
     * answer - or one error instead. The listener is called on the provider client's threads.
     */
    public void stream(AgentRun run, Listener listener) {
        streamRound(cached(run), new ArrayList<>(run.messages()), 0, new StringBuilder(), false, listener);
    }

    /**
     * The rounds of one answer under one cache key: every round sends the whole conversation again, the provider reads
     * what they share from its cache (docs/architecture/ai-features.md "Prompt caching") - the model sees the same.
     */
    private static AgentRun cached(AgentRun run) {
        return new AgentRun(run.tenant(), run.userId(), run.model(), run.key(),
                run.options().withCacheKey("answer-" + UUID.randomUUID()), run.messages(), run.tools(), run.context(),
                run.maxRounds());
    }

    private void streamRound(AgentRun run, List<ChatMessage> messages, int round, StringBuilder answer,
                             boolean askedAgain, Listener listener) {
        if (round > 0 && run.context().stopRequested()) {
            listener.onError().accept(new ServiceException(HttpStatus.CONFLICT, "answer.stopped", "The answer was stopped"));
            return;
        }
        boolean separate = !answer.isEmpty();
        boolean tools = !askedAgain && toolsCallable(run, round);
        if (tools && round == run.maxRounds() - 2) {
            messages.add(UserMessage.from(LAST_TOOL_ROUND));
        }
        aiModels.streamChat(run.model(), run.key().apiKey(), messages, run.tools(), tools,
                run.options(), new StreamingChatResponseHandler() {
                    private boolean first = true;

                    @Override
                    public void onPartialResponse(String part) {
                        if (first && separate) {
                            listener.onPart().accept("\n\n");
                        }
                        first = false;
                        listener.onPart().accept(part);
                    }

                    /** The model's reasoning, shown while it thinks - never stored. */
                    @Override
                    public void onPartialThinking(PartialThinking thinking) {
                        listener.onThinking().accept(thinking.text());
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        String completed;
                        try {
                            record(run, messages, response);
                            appendText(answer, response);
                            int sourcesBefore = run.context().sources().size();
                            if (continueWithTools(run, messages, response, tools,
                                    request -> listener.onTool().accept(toolbox.step(run.context(), request)))) {
                                if (run.context().sources().size() > sourcesBefore) {
                                    listener.onSources().accept(List.copyOf(run.context().sources()));
                                }
                                streamRound(run, messages, round + 1, answer, askedAgain, listener);
                                return;
                            }
                            if (!askedAgain && askAgainIfEmpty(run, round, tools, response, messages)) {
                                streamRound(run, messages, round + 1, answer, true, listener);
                                return;
                            }
                            completed = requireAnswer(run, round, tools, finalAnswer(answer, response), response);
                        } catch (ServiceException e) {
                            LOG.warn("The answer for tenant {} failed: {}", run.tenant().id(), e.getMessage());
                            listener.onError().accept(e);
                            return;
                        } catch (RuntimeException e) {
                            LOG.error("The answer for tenant {} failed", run.tenant().id(), e);
                            listener.onError().accept(notCompleted());
                            return;
                        } catch (Error e) {
                            // Told before it is thrown on: an answer collected in one piece (run) waits for the end of
                            // every round, and the provider's thread dies with the error.
                            LOG.error("The answer for tenant {} failed", run.tenant().id(), e);
                            listener.onError().accept(notCompleted());
                            throw e;
                        }
                        listener.onAnswer().accept(completed);
                    }

                    @Override
                    public void onError(Throwable error) {
                        listener.onError().accept(error instanceof ServiceException failure ? failure
                                : new ServiceException(HttpStatus.BAD_GATEWAY, "provider.failed",
                                        run.model().provider().name() + " failed - please try again",
                                        Map.of("name", run.model().provider().name())));
                    }
                });
    }

    private static ServiceException notCompleted() {
        return new ServiceException(HttpStatus.INTERNAL_SERVER_ERROR, "chat.answerNotCompleted",
                "The answer could not be completed");
    }

    /**
     * Runs the tool calls of a response and adds them with their results to the messages, followed by the files the
     * tools returned - images, an original PDF, audio, video (a tool result can only be text with every provider) -
     * false when there are none (or the round may call none - the last, or the one asking again): the answer is
     * complete. A changing call of the connected software stops the answer before anything of the response runs: the
     * user decides first. beforeTool hears each call before it runs - a tool can take minutes, and a stream silent
     * that long looks dead.
     */
    private boolean continueWithTools(AgentRun run, List<ChatMessage> messages, ChatResponse response,
                                      boolean toolsCallable, Consumer<ToolExecutionRequest> beforeTool) {
        AiMessage message = response.aiMessage();
        if (!message.hasToolExecutionRequests() || !toolsCallable) {
            return false;
        }
        for (ToolExecutionRequest request : message.toolExecutionRequests()) {
            Optional<McpTools.Offered> asking = toolbox.askingFirst(run.context(), request);
            if (asking.isPresent()) {
                run.context().pendingCall(new ToolContext.PendingCall(asking.get(), request));
                return false;
            }
        }
        messages.add(message);
        List<Content> attachments = new ArrayList<>();
        for (ToolExecutionRequest request : message.toolExecutionRequests()) {
            beforeTool.accept(request);
            ToolResult result = toolbox.execute(run.context(), request);
            messages.add(ToolExecutionResultMessage.from(request, result.text()));
            attachments.addAll(result.attachmentContents());
        }
        if (!attachments.isEmpty()) {
            attachments.addFirst(TextContent.from(TOOL_FILES));
            messages.add(UserMessage.from(attachments));
        }
        return true;
    }

    /** Whether the round may call tools: not the last, which must answer in text. */
    private static boolean toolsCallable(AgentRun run, int round) {
        return round < run.maxRounds() - 1;
    }

    /**
     * A round StreamStop ended has no token count from the provider, which still bills the whole prompt - its input
     * is estimated from the text of the messages sent.
     */
    private void record(AgentRun run, List<ChatMessage> messages, ChatResponse response) {
        BigDecimal cost = usage.record(run.tenant().id(), run.userId(), run.model(), run.key().source(),
                response.tokenUsage(), characters(messages));
        run.context().count(CallTokens.of(response.tokenUsage()), cost);
    }

    private static long characters(List<ChatMessage> messages) {
        long characters = 0;
        for (ChatMessage message : messages) {
            characters += switch (message) {
                case SystemMessage system -> system.text().length();
                case UserMessage user -> textLength(user.contents());
                case AiMessage ai -> ai.text() == null ? 0 : ai.text().length();
                case ToolExecutionResultMessage result -> result.text().length();
                default -> 0;
            };
        }
        return characters;
    }

    private static long textLength(List<Content> contents) {
        long length = 0;
        for (Content content : contents) {
            if (content instanceof TextContent text) {
                length += text.text().length();
            }
        }
        return length;
    }

    private static void appendText(StringBuilder answer, ChatResponse response) {
        String text = response.aiMessage().text();
        if (isBlank(text)) {
            return;
        }
        if (!answer.isEmpty()) {
            answer.append("\n\n");
        }
        answer.append(text);
    }

    /**
     * The answer: the last round's text. Models often write a draft next to their tool calls and repeat it once they
     * have the results (seen with Gemini) - the rounds' texts together would say it twice. Only when the last round has
     * no text, the earlier ones stand.
     */
    private static String finalAnswer(StringBuilder allRounds, ChatResponse last) {
        String text = last.aiMessage().text();
        return withoutHistoryNotes(withoutBlankRuns(isBlank(text) ? allRounds.toString() : text));
    }

    /**
     * A model that ended without a word, though it was not cut off, is asked once more - from what it found, without
     * tools (ASK_AGAIN): a Qwen model on its own server wrote its answer into its reasoning and ended the round empty,
     * or wanted to search on in the last round, which has no tools (October 2026); the found passages were lost with
     * the 502. The empty response is not added: providers refuse an empty assistant message, and its tool calls would
     * stand without results. True when it was asked.
     * <p>
     * Judged on the round itself, not on the rounds together: an earlier round's draft next to its tool calls ("let me
     * look") would otherwise stand as the answer, and the model never be asked for the real one.
     */
    private static boolean askAgainIfEmpty(AgentRun run, int round, boolean tools, ChatResponse last,
                                           List<ChatMessage> messages) {
        boolean empty = isBlank(last.aiMessage().text()) && last.finishReason() != FinishReason.LENGTH
                && run.context().pendingCall() == null && run.context().createdFiles().isEmpty();
        if (!empty) {
            return false;
        }
        logNoAnswer(run, round, tools, last, "asked again");
        messages.add(UserMessage.from(ASK_AGAIN));
        return true;
    }

    /**
     * An answer without text is a failure, not an answer, and the asker learns why. A model that thinks spends its
     * output on reasoning first - with too low a limit nothing is left for the answer (Qwen on vLLM, October 2026: an
     * empty answer, no error); asking again would be cut off the same way. An answer that stopped at a changing tool
     * call or created files may have no text of its own. Otherwise an empty answer comes here only after it was asked
     * again ({@link #askAgainIfEmpty}).
     */
    private static String requireAnswer(AgentRun run, int round, boolean tools, String answer, ChatResponse last) {
        if (run.context().pendingCall() != null || !run.context().createdFiles().isEmpty()) {
            return answer;
        }
        String model = run.model().displayName();
        // Cut off before its first word: an earlier round's "let me look" is no answer either.
        if (isBlank(last.aiMessage().text()) && last.finishReason() == FinishReason.LENGTH) {
            logNoAnswer(run, round, tools, last, "cut off by the output limit");
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "chat.outputLimitUsedByReasoning", model
                    + " used up its output limit on its reasoning "
                    + "before it answered - an admin raises the model's output tokens in the model catalog",
                    Map.of("model", model));
        }
        if (answer.isBlank()) {
            logNoAnswer(run, round, tools, last, "empty again after asking");
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "chat.noAnswer", model
                    + " gave no answer - please try again",
                    Map.of("model", model));
        }
        return answer;
    }

    /**
     * What tells the causes of an empty answer apart - the whole answer in the reasoning, a round ended empty, a last
     * round without tools the model still wanted to call some in (a Qwen model on its own server, October 2026: no
     * answer now and then, and nothing in the log said why). Lengths and counts only: the content is the company's.
     * The round asking again may lie beyond the limit of rounds - its line says "after asking".
     */
    private static void logNoAnswer(AgentRun run, int round, boolean tools, ChatResponse last, String outcome) {
        AiMessage message = last.aiMessage();
        LOG.warn("{} gave no answer for tenant {}: round {}, limit {}, tools callable {}, finish reason {}, "
                        + "text {} characters, reasoning {} characters, {} tool calls - {}",
                run.model().displayName(), run.tenant().id(), round + 1, run.maxRounds(), tools,
                last.finishReason(), length(message.text()), length(message.thinking()),
                message.hasToolExecutionRequests() ? message.toolExecutionRequests().size() : 0, outcome);
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    /**
     * The notes the chat's history puts on earlier answers are for the model alone - a model copies them into its own
     * answer now and then (Gemini 2.5 Flash, "[Created with a tool: ...]", 10 October 2026). A line that is nothing but
     * such a note is left out.
     */
    static String withoutHistoryNotes(String text) {
        return HISTORY_NOTE.matcher(text).replaceAll("").strip();
    }

    /**
     * A model caught in a loop of blanks (Gemini padding a table) is stopped while it streams (AiModels.StreamStop);
     * an answer that was not streamed (/api/v1/chat) loses such runs here - nobody can read them, and the browser
     * cannot even render them.
     */
    static String withoutBlankRuns(String text) {
        return text.replaceAll("[ \\t]{200,}", " ").strip();
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.isBlank();
    }
}
