package de.alpdesk.services.ai;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.services.common.ServiceException;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatRequestParameters;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.bedrock.BedrockCachePointPlacement;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.bedrock.BedrockStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.entities.assistant.Reasoning;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GeneratedImageHelper;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.output.TokenUsage;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkServiceException;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The one place that talks to the AI providers through langchain4j: builds the provider's model for a catalog entry
 * and a key - for the models on own servers (OLLAMA, OPENAI_COMPATIBLE, BEDROCK, TEI) at the entry's address
 * (docs/architecture/ai-features.md "Own model servers") -, calls it, and turns a provider's failure into a {@link ServiceException} the caller can show. The specs
 * replace this class with a fake - no spec ever calls a provider.
 * <p>
 * A provider's failure is never 401/403 towards our caller: the frontend would take that for its own session ending.
 */
@Singleton
public class AiModels {

    private static final Logger LOG = LoggerFactory.getLogger(AiModels.class);

    /** Ollama's context length where the catalog gives none: enough for the excerpts, files and tools of a question. */
    static final int DEFAULT_CONTEXT_LENGTH = 32_768;
    /**
     * A chat call is asked once: langchain4j's retries (two by default, on a timeout too) made the user wait three times
     * app.ai.timeout for the 502, and each attempt may be billed. The embedding and reading a scan keep them - they run
     * in the background, and a large import meets rate limits a retry gets past.
     */
    private static final int NO_RETRIES = 0;
    /** langchain4j's default, written out: what a call in the background ({@link #chatInBackground}) is asked again. */
    private static final int BACKGROUND_RETRIES = 2;

    /**
     * How long a search waits for the reranker - far shorter than app.ai.timeout: the question waits, and without the
     * reranker the search answers all the same, in the hybrid order.
     */
    private static final Duration RERANK_TIMEOUT = Duration.ofSeconds(30);

    private final Duration timeout;
    private final int maxOutputTokens;
    private final ModelServers servers;
    private final BedrockClients bedrock;
    /** Where Gemini's image models are asked: null = Google's address; another only for ModelServerSpec's fake server. */
    private final @Nullable String geminiImageAddress;
    /** Watches the streamed answers for silence ({@link StreamStop}). */
    private final ScheduledExecutorService watchdog = watchdog();

    @Inject
    public AiModels(@Value("${app.ai.timeout}") Duration timeout, @Value("${app.ai.max-output-tokens}") int maxOutputTokens,
                    ModelServers servers, BedrockClients bedrock) {
        this(timeout, maxOutputTokens, servers, bedrock, null);
    }

    AiModels(Duration timeout, int maxOutputTokens, ModelServers servers, BedrockClients bedrock,
             @Nullable String geminiImageAddress) {
        this.timeout = timeout;
        this.maxOutputTokens = maxOutputTokens;
        this.servers = servers;
        this.bedrock = bedrock;
        this.geminiImageAddress = geminiImageAddress;
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
    }

    /**
     * The answer - with tool calls instead of (or next to) text when the model uses one of the tools offered. options:
     * the tenant's creativity and reasoning, sent as far as the catalog says the model takes them.
     */
    public ChatResponse chat(AiModel model, @Nullable String apiKey, List<ChatMessage> messages,
                             List<ToolSpecification> tools, ChatOptions options) {
        return chat(model, apiKey, messages, tools, true, options);
    }

    /**
     * As {@link #chat(AiModel, String, List, List, ChatOptions)}; toolsCallable false: the tools are listed but none
     * may be called ({@link #request}). The agent loop streams every round ({@link #streamChat}); this overload is the
     * specs' fake's, which answers a streamed call from it.
     */
    public ChatResponse chat(AiModel model, @Nullable String apiKey, List<ChatMessage> messages,
                             List<ToolSpecification> tools, boolean toolsCallable, ChatOptions options) {
        try {
            return chatModel(model, apiKey, Sent.of(model, options, maxOutputTokens), NO_RETRIES)
                    .chat(request(model, messages, tools, toolsCallable));
        } catch (RuntimeException e) {
            throw providerFailure(model, e);
        }
    }

    /**
     * As {@link #chat(AiModel, String, List, List, ChatOptions)} without tools and settings, but asked again like the
     * embedding: the image model reading a scan's pages in the background, where nobody waits for the 502 and one rate
     * limit at page 150 of 200 would fail the whole document - its retry reading every page again.
     */
    public ChatResponse chatInBackground(AiModel model, @Nullable String apiKey, List<ChatMessage> messages) {
        try {
            return chatModel(model, apiKey, Sent.of(model, ChatOptions.NONE, maxOutputTokens), BACKGROUND_RETRIES)
                    .chat(request(model, messages, List.of(), true));
        } catch (RuntimeException e) {
            throw providerFailure(model, e);
        }
    }

    /**
     * Streams the answer to the handler: its parts as they come, then the whole response - or one error, already
     * turned into a {@link ServiceException}. The handler is called on the provider client's threads.
     */
    public void streamChat(AiModel model, @Nullable String apiKey, List<ChatMessage> messages, List<ToolSpecification> tools,
                           ChatOptions options, StreamingChatResponseHandler handler) {
        streamChat(model, apiKey, messages, tools, true, options, handler);
    }

    /** As {@link #streamChat(AiModel, String, List, List, ChatOptions, StreamingChatResponseHandler)}, toolsCallable as at chat. */
    public void streamChat(AiModel model, @Nullable String apiKey, List<ChatMessage> messages, List<ToolSpecification> tools,
                           boolean toolsCallable, ChatOptions options, StreamingChatResponseHandler handler) {
        StreamStop guarded = new StreamStop(model, handler, timeout, watchdog);
        try {
            streamingChatModel(model, apiKey, Sent.of(model, options, maxOutputTokens))
                    .chat(request(model, messages, tools, toolsCallable, options.cacheKey()), guarded);
        } catch (RuntimeException e) {
            guarded.onError(e);
        }
    }

    /**
     * One thread for the silence checks. A check cancelled at the end of its answer is removed at once: kept until it
     * was due (app.ai.timeout), it would hold the answer - its messages, files sent with the question included - that
     * long.
     */
    static ScheduledExecutorService watchdog() {
        ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("ai-stream-watchdog").factory());
        watchdog.setRemoveOnCancelPolicy(true);
        return watchdog;
    }

    /**
     * Passes a streamed answer on and ends it when the provider does not: once, whatever comes afterwards.
     * <ul>
     * <li><b>Runaway</b>: a model caught in a loop of blanks (Gemini 2.5 padding a Markdown table: 366 000 spaces
     * after 764 characters, until the output limit; the browser could not even render it). After {@link #MAX_BLANK_RUN}
     * blanks in a row the stream is cancelled and the text so far completes the answer - its tokens are unknown then,
     * the usage records it as estimated.</li>
     * <li><b>Reasoning in a loop</b>: a model repeating the same block of its reasoning word for word
     * ({@link CycleDetector}: a Qwen model on its own server for minutes, its answer planned and never written, until
     * the output limit). The stream is cancelled and the round completes without text, so the agent loop asks the
     * model once more for its answer, from what it found. Only the reasoning is watched: an answer may repeat a block
     * on purpose (the same configuration for several devices), and the asker sees it and can stop it.</li>
     * <li><b>Silence</b>: the request's timeout only bounds the wait for the response's headers, a stream that falls
     * silent after them never ends (ModelServerSpec) - the answer would wait for good, the sandbox leased for it stay
     * taken. Every sign of the stream counts - text, reasoning, a tool call streaming -; after the limit (app.ai.timeout)
     * without one the stream is cancelled and the answer fails with 502.</li>
     * </ul>
     * Failures become ServiceExceptions as everywhere here. The provider's thread calls the parts, the watchdog's the
     * silence check: {@link #end} lets exactly one of them end the answer.
     */
    static final class StreamStop implements StreamingChatResponseHandler {

        static final int MAX_BLANK_RUN = 200;

        private final AiModel model;
        private final StreamingChatResponseHandler handler;
        private final long limitNanos;
        private final ScheduledExecutorService watchdog;
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile long lastSign = System.nanoTime();
        private volatile @Nullable StreamingHandle stream;
        private volatile ScheduledFuture<?> check;
        // Text, blank run and the reasoning's loop: the provider's thread alone.
        private final StringBuilder text = new StringBuilder();
        private int blankRun;
        private final CycleDetector reasoningLoop = new CycleDetector();
        private long reasoningChars;

        StreamStop(AiModel model, StreamingChatResponseHandler handler, Duration limit, ScheduledExecutorService watchdog) {
            this.model = model;
            this.handler = handler;
            this.limitNanos = limit.toNanos();
            this.watchdog = watchdog;
            this.check = watchdog.schedule(this::checkSilence, limitNanos, TimeUnit.NANOSECONDS);
        }

        /** The one end of the answer - false for whoever comes second. */
        private boolean end() {
            if (!ended.compareAndSet(false, true)) {
                return false;
            }
            check.cancel(false);
            return true;
        }

        /** A sign of the stream - false once the answer ended. */
        private boolean sign(@Nullable StreamingHandle handle) {
            if (handle != null) {
                stream = handle;
            }
            lastSign = System.nanoTime();
            return !ended.get();
        }

        /** Looks again once the limit after the last sign is reached - one check at a time, not one per part. */
        private void checkSilence() {
            try {
                long silent = System.nanoTime() - lastSign;
                if (ended.get()) {
                    return;
                }
                if (silent < limitNanos) {
                    check = watchdog.schedule(this::checkSilence, limitNanos - silent, TimeUnit.NANOSECONDS);
                    return;
                }
                if (!end()) {
                    return;
                }
                LOG.warn("{} model {} sent nothing for {} s - its answer was stopped", model.provider(), model.modelName(),
                        TimeUnit.NANOSECONDS.toSeconds(limitNanos));
                cancelStream();
                String name = name(model);
                handler.onError(gateway("provider.stalled", name + " stopped answering - please try again",
                        Map.of("name", name)));
            } catch (RuntimeException e) {
                // The watchdog's thread: nobody else would log it.
                LOG.error("Ending a silent answer of {} failed", model.modelName(), e);
            }
        }

        private void cancelStream() {
            StreamingHandle current = stream;
            if (current != null) {
                current.cancel();
            }
        }

        @Override
        public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            pass(partialResponse.text(), context.streamingHandle());
        }

        /** Providers that do not hand over their stream: it cannot be cancelled, the blanks are left out still. */
        @Override
        public void onPartialResponse(String partialResponse) {
            pass(partialResponse, null);
        }

        private void pass(String part, @Nullable StreamingHandle handle) {
            if (!sign(handle)) {
                return;
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                blankRun = c == ' ' || c == '\t' ? blankRun + 1 : 0;
                if (blankRun > MAX_BLANK_RUN) {
                    stopRunaway(part.substring(0, i));
                    return;
                }
            }
            text.append(part);
            handler.onPartialResponse(part);
        }

        private void stopRunaway(String lastPart) {
            if (!end()) {
                return;
            }
            LOG.warn("{} model {} ran away in blanks - its answer was stopped", model.provider(), model.modelName());
            cancelStream();
            text.append(lastPart);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(text.toString().strip()))
                    .finishReason(FinishReason.LENGTH)
                    .build());
        }

        /** The model's reasoning as it streams - passed on, for the chat to show (OpenAI-compatible, Claude, Gemini, Ollama). */
        @Override
        public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
            think(partialThinking, context.streamingHandle());
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking) {
            think(partialThinking, null);
        }

        private void think(PartialThinking partialThinking, @Nullable StreamingHandle handle) {
            String part = partialThinking.text();
            if (!sign(handle) || part == null || part.isEmpty()) {
                return;
            }
            handler.onPartialThinking(partialThinking);
            reasoningChars += part.length();
            CycleDetector.Cycle loop = reasoningLoop.add(part);
            if (loop != null) {
                stopReasoningLoop(loop);
            }
        }

        private void stopReasoningLoop(CycleDetector.Cycle loop) {
            if (!end()) {
                return;
            }
            LOG.warn("{} model {} repeated its reasoning: {} copies of {} lines or cuts ({} characters) after {} "
                            + "characters of reasoning - its answer was stopped", model.provider(), model.modelName(), loop.copies(),
                    loop.units(), loop.chars(), reasoningChars);
            cancelStream();
            // Not LENGTH: the agent loop asks a model again that was not cut off by its limit.
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(text.toString().strip()))
                    .finishReason(FinishReason.OTHER)
                    .build());
        }

        /** A long tool call (a whole file for create_file) streams without text - a sign all the same. */
        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
            sign(context.streamingHandle());
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall) {
            sign(null);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            if (end()) {
                handler.onCompleteResponse(completeResponse);
            }
        }

        @Override
        public void onError(Throwable error) {
            if (end()) {
                handler.onError(error instanceof RuntimeException runtime ? providerFailure(model, runtime)
                        : providerFailure(model, new RuntimeException(error)));
            }
        }
    }

    /**
     * The embeddings of the segments, in their order, with the token usage the provider reported (may be null). A
     * server's vectors are checked against the catalog's dimension: it cannot be asked for one (many refuse
     * "dimensions"), and vectors of another length would break the tenant's embedding table.
     */
    public Response<List<Embedding>> embed(AiModel model, @Nullable String apiKey, List<TextSegment> segments) {
        Response<List<Embedding>> response;
        try {
            response = embeddingModel(model, apiKey).embedAll(segments);
        } catch (RuntimeException e) {
            throw providerFailure(model, e);
        }
        for (Embedding embedding : response.content()) {
            if (embedding.dimension() != model.dimension()) {
                throw gateway("provider.dimensionMismatch", name(model) + " returns vectors of length "
                        + embedding.dimension() + ", the model catalog says " + model.dimension(),
                        Map.of("name", name(model), "dimension", String.valueOf(embedding.dimension()),
                                "expected", String.valueOf(model.dimension())));
            }
        }
        return response;
    }

    /**
     * The reranker's score of every text against the query, in the texts' order (docs/architecture/ai-features.md
     * "Reranking"). Asked once, without retries and with a short timeout: the search waits for it, and the caller keeps
     * the hybrid order when it fails.
     */
    public List<Double> rerank(AiModel model, @Nullable String apiKey, String query, List<String> texts) {
        try {
            return scoringModel(model, apiKey).scoreAll(texts.stream().map(TextSegment::from).toList(), query).content();
        } catch (RuntimeException e) {
            throw providerFailure(model, e);
        }
    }

    private ScoringModel scoringModel(AiModel model, @Nullable String apiKey) {
        return switch (model.provider()) {
            case TEI -> new TeiScoringModel(withoutRedirects().connectTimeout(RERANK_TIMEOUT).readTimeout(RERANK_TIMEOUT)
                    .build(), server(model), apiKey);
            // AiModelService allows TEI only for a reranker.
            case ANTHROPIC, OPENAI, GEMINI, OLLAMA, OPENAI_COMPATIBLE, BEDROCK, TAVILY ->
                    throw new IllegalStateException(model.provider() + " has no rerankers here");
        };
    }

    /** The images an image model takes in one call - more are refused before calling it (docs/roadmap.md point 25). */
    public int imagesPerCall(AiModel model) {
        return switch (model.provider()) {
            // Google recommends up to three for gemini-2.5-flash-image; five leave room for a house and its landscape.
            case GEMINI -> 5;
            // AiModelService refuses image models of the other providers.
            case ANTHROPIC, OPENAI, OLLAMA, OPENAI_COMPATIBLE, BEDROCK, TAVILY, TEI -> 0;
        };
    }

    /**
     * An image of the image model (docs/roadmap.md point 25): a new one from the instruction alone, or one made of the
     * images sent with it - changed, or combined. The result may hold no image: the model refused (its safety filter)
     * or answered in text only; its text says why, and the call is billed all the same.
     */
    public CreatedImage createImage(AiModel model, @Nullable String apiKey, String instruction, List<Image> images) {
        ChatResponse response;
        try {
            response = imageModel(model, apiKey).chat(ChatRequest.builder().messages(imageRequest(instruction, images)).build());
        } catch (RuntimeException e) {
            throw providerFailure(model, e);
        }
        return createdImage(response);
    }

    /**
     * Gemini's image models answer through its chat API, the images as parts of the answer - langchain4j's
     * GoogleAiGeminiImageModel takes one image only. Without tools and thinking: the image models take neither.
     */
    private ChatModel imageModel(AiModel model, @Nullable String apiKey) {
        return switch (model.provider()) {
            case GEMINI -> GoogleAiGeminiChatModel.builder().baseUrl(geminiImageAddress).apiKey(apiKey)
                    .modelName(model.modelName()).timeout(timeout).maxRetries(NO_RETRIES).build();
            case ANTHROPIC, OPENAI, OLLAMA, OPENAI_COMPATIBLE, BEDROCK, TAVILY, TEI ->
                    throw new IllegalStateException(model.provider() + " has no image models here");
        };
    }

    /** The instruction first, then the images in the order the user named them. */
    static UserMessage imageRequest(String instruction, List<Image> images) {
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(instruction));
        for (Image image : images) {
            contents.add(ImageContent.from(image));
        }
        return UserMessage.from(contents);
    }

    /** The first image of the answer (Gemini sends one), its text, and the usage the provider reported. */
    static CreatedImage createdImage(ChatResponse response) {
        String text = response.aiMessage().text() == null ? "" : response.aiMessage().text().strip();
        List<Image> generated = GeneratedImageHelper.getGeneratedImages(response.aiMessage());
        if (generated.isEmpty()) {
            return new CreatedImage(null, null, text, response.tokenUsage());
        }
        Image image = generated.getFirst();
        String mimeType = image.mimeType() == null || image.mimeType().isBlank() ? "image/png" : image.mimeType();
        return new CreatedImage(Base64.getDecoder().decode(image.base64Data()), mimeType, text, response.tokenUsage());
    }

    /**
     * What an image model gave back: the image (null if it created none) with its type, its text (empty if none) and
     * the usage the provider reported (may be null).
     */
    public record CreatedImage(@Nullable byte[] content, @Nullable String mimeType, String text,
                               @Nullable TokenUsage usage) {

        public boolean hasImage() {
            return content != null;
        }
    }

    private ChatModel chatModel(AiModel model, @Nullable String apiKey, Sent sent, int retries) {
        return switch (model.provider()) {
            case ANTHROPIC -> AnthropicChatModel.builder().apiKey(apiKey).modelName(model.modelName())
                    .maxTokens(sent.maxTokens()).temperature(sent.temperature()).timeout(timeout)
                    .thinkingType(sent.anthropicThinking()).thinkingBudgetTokens(sent.thinkingBudget())
                    .returnThinking(sent.keepsThinking()).sendThinking(sent.keepsThinking())
                    .maxRetries(retries).build();
            case OPENAI -> OpenAiChatModel.builder().apiKey(apiKey).modelName(model.modelName())
                    .maxCompletionTokens(sent.maxTokens()).temperature(sent.temperature())
                    .reasoningEffort(sent.openAiEffort()).timeout(timeout).maxRetries(retries).build();
            case GEMINI -> GoogleAiGeminiChatModel.builder().apiKey(apiKey).modelName(model.modelName())
                    .maxOutputTokens(sent.maxTokens()).temperature(sent.temperature())
                    .thinkingConfig(sent.geminiThinking()).returnThinking(true).sendThinking(true)
                    .timeout(timeout).maxRetries(retries).build();
            case OLLAMA -> OllamaChatModel.builder().baseUrl(server(model)).customHeaders(bearer(apiKey))
                    .httpClientBuilder(withoutRedirects()).logRequests(false).logResponses(false)
                    .modelName(model.modelName()).numCtx(contextLength(model)).numPredict(sent.maxTokens())
                    .temperature(sent.temperature()).think(sent.ollamaThink()).timeout(timeout).maxRetries(retries)
                    .build();
            case OPENAI_COMPATIBLE -> OpenAiChatModel.builder().baseUrl(server(model)).apiKey(apiKey)
                    .httpClientBuilder(withoutRedirects()).logRequests(false).logResponses(false)
                    .modelName(model.modelName()).maxTokens(sent.maxTokens()).temperature(sent.temperature())
                    .reasoningEffort(sent.serverEffort()).timeout(timeout).maxRetries(retries).build();
            case BEDROCK -> BedrockChatModel.builder().client(bedrock.client(server(model), apiKey))
                    .modelId(model.modelName()).defaultRequestParameters(bedrockParameters(sent))
                    .returnThinking(sent.keepsThinking()).sendThinking(sent.keepsThinking())
                    .logRequests(false).logResponses(false).maxRetries(retries).build();
            // AiModelService keeps Tavily to the web search's catalog entry: it has no models.
            case TAVILY, TEI -> throw new IllegalStateException(model.provider() + " offers no chat models");
        };
    }

    private StreamingChatModel streamingChatModel(AiModel model, @Nullable String apiKey, Sent sent) {
        return switch (model.provider()) {
            case ANTHROPIC -> AnthropicStreamingChatModel.builder().apiKey(apiKey).modelName(model.modelName())
                    .maxTokens(sent.maxTokens()).temperature(sent.temperature()).timeout(timeout)
                    .thinkingType(sent.anthropicThinking()).thinkingBudgetTokens(sent.thinkingBudget())
                    .returnThinking(sent.keepsThinking()).sendThinking(sent.keepsThinking()).build();
            case OPENAI -> OpenAiStreamingChatModel.builder().apiKey(apiKey).modelName(model.modelName())
                    .maxCompletionTokens(sent.maxTokens()).temperature(sent.temperature())
                    .reasoningEffort(sent.openAiEffort()).timeout(timeout).build();
            case GEMINI -> GoogleAiGeminiStreamingChatModel.builder().apiKey(apiKey).modelName(model.modelName())
                    .maxOutputTokens(sent.maxTokens()).temperature(sent.temperature())
                    .thinkingConfig(sent.geminiThinkingShown()).returnThinking(true).sendThinking(true).timeout(timeout)
                    .build();
            // Ollama and vLLM hand their reasoning over ("thinking", "reasoning") - streamed to the chat, never sent back.
            case OLLAMA -> OllamaStreamingChatModel.builder().baseUrl(server(model)).customHeaders(bearer(apiKey))
                    .httpClientBuilder(withoutRedirects()).logRequests(false).logResponses(false)
                    .modelName(model.modelName()).numCtx(contextLength(model)).numPredict(sent.maxTokens())
                    .temperature(sent.temperature()).think(sent.ollamaThink()).returnThinking(true).timeout(timeout)
                    .build();
            case OPENAI_COMPATIBLE -> OpenAiStreamingChatModel.builder().baseUrl(server(model)).apiKey(apiKey)
                    .httpClientBuilder(withoutRedirects()).logRequests(false).logResponses(false)
                    .modelName(model.modelName()).maxTokens(sent.maxTokens()).temperature(sent.temperature())
                    .reasoningEffort(sent.serverEffort()).returnThinking(true).timeout(timeout).build();
            case BEDROCK -> BedrockStreamingChatModel.builder().client(bedrock.streamingClient(server(model), apiKey))
                    .modelId(model.modelName()).defaultRequestParameters(bedrockParameters(sent))
                    .returnThinking(sent.keepsThinking()).sendThinking(sent.keepsThinking())
                    .logRequests(false).logResponses(false).build();
            case TAVILY, TEI -> throw new IllegalStateException(model.provider() + " offers no chat models");
        };
    }

    /**
     * What a call sends of the tenant's settings, per provider - null leaves a parameter out (the model's default).
     * Only what the catalog says the model takes: a temperature (reasoning models refuse one), a reasoning effort.
     * Claude thinks only without a temperature, so thinking wins there. Thinking tokens count against the output
     * limit, so the budget is added to it. The limit is the catalog entry's, else the installation's: a model that
     * thinks on its own (Qwen on vLLM) spends it on reasoning first and must have room for its answer after.
     */
    record Sent(@Nullable Double temperature, @Nullable Reasoning reasoning, int maxTokens) {

        static Sent of(AiModel model, ChatOptions options, int defaultMaxOutputTokens) {
            Reasoning reasoning = model.steersReasoning() ? options.reasoning() : null;
            boolean claudeThinks = (model.provider() == AiProvider.ANTHROPIC || model.provider() == AiProvider.BEDROCK)
                    && thinks(reasoning);
            Double temperature = model.takesTemperature() && options.creativity() != null && !claudeThinks
                    ? options.creativity().temperature : null;
            int maxOutputTokens = model.maxOutputTokens() != null ? model.maxOutputTokens() : defaultMaxOutputTokens;
            return new Sent(temperature, reasoning, maxOutputTokens + (reasoning == null ? 0 : reasoning.budgetTokens));
        }

        private static boolean thinks(@Nullable Reasoning reasoning) {
            return reasoning != null && reasoning != Reasoning.OFF;
        }

        String anthropicThinking() {
            return thinks(reasoning) ? "enabled" : null;
        }

        Integer thinkingBudget() {
            return thinks(reasoning) ? reasoning.budgetTokens : null;
        }

        /** Claude's thinking must go back with a tool's result in the agent loop - langchain4j keeps it for that. */
        Boolean keepsThinking() {
            return thinks(reasoning) ? Boolean.TRUE : null;
        }

        /** OpenAI's lowest effort is "minimal" - its reasoning models cannot think not at all. */
        String openAiEffort() {
            return reasoning == null ? null : reasoning == Reasoning.OFF ? "minimal" : reasoning.name().toLowerCase(Locale.ROOT);
        }

        /** Other servers know low/medium/high at most (vLLM, gpt-oss): off sends nothing. */
        String serverEffort() {
            return thinks(reasoning) ? reasoning.name().toLowerCase(Locale.ROOT) : null;
        }

        /** Gemini's budget 0 switches thinking off (2.5 Flash; 2.5 Pro cannot - choose "niedrig" there). */
        GeminiThinkingConfig geminiThinking() {
            return reasoning == null ? null : GeminiThinkingConfig.builder().thinkingBudget(reasoning.budgetTokens).build();
        }

        /** For the chat's stream: Gemini's thoughts as summaries, shown while it thinks. */
        GeminiThinkingConfig geminiThinkingShown() {
            return reasoning == null ? null : GeminiThinkingConfig.builder().thinkingBudget(reasoning.budgetTokens)
                    .includeThoughts(thinks(reasoning) ? Boolean.TRUE : null).build();
        }

        Boolean ollamaThink() {
            return reasoning == null ? null : thinks(reasoning);
        }
    }

    /**
     * What Bedrock is sent of the tenant's settings: Claude's thinking goes as its reasoning_config (langchain4j's
     * enableReasoning) - a model that thinks no other way is not marked to steer its reasoning in the catalog.
     */
    private static BedrockChatRequestParameters bedrockParameters(Sent sent) {
        return BedrockChatRequestParameters.builder().enableReasoning(sent.thinkingBudget())
                .maxOutputTokens(sent.maxTokens()).temperature(sent.temperature()).build();
    }

    private EmbeddingModel embeddingModel(AiModel model, @Nullable String apiKey) {
        return switch (model.provider()) {
            case OPENAI -> OpenAiEmbeddingModel.builder().apiKey(apiKey).modelName(model.modelName())
                    .dimensions(model.dimension()).timeout(timeout).build();
            case GEMINI -> GoogleAiEmbeddingModel.builder().apiKey(apiKey).modelName(model.modelName())
                    .outputDimensionality(model.dimension()).timeout(timeout).build();
            case OLLAMA -> OllamaEmbeddingModel.builder().baseUrl(server(model)).customHeaders(bearer(apiKey))
                    .httpClientBuilder(withoutRedirects()).logRequests(false).logResponses(false)
                    .modelName(model.modelName()).timeout(timeout).build();
            case OPENAI_COMPATIBLE -> OpenAiEmbeddingModel.builder().baseUrl(server(model)).apiKey(apiKey)
                    .httpClientBuilder(withoutRedirects()).logRequests(false).logResponses(false)
                    .modelName(model.modelName()).timeout(timeout).build();
            // AiModelService refuses Anthropic embedding models: Anthropic has no embedding API.
            case ANTHROPIC -> throw new IllegalStateException("Anthropic offers no embedding models");
            // AiModelService takes Bedrock's chat models only - the company's knowledge keeps its own embedding model.
            case BEDROCK -> throw new IllegalStateException("Bedrock's embedding models are not offered here");
            case TAVILY, TEI -> throw new IllegalStateException(model.provider() + " offers no embedding models here");
        };
    }

    /** The model's server - refused if it may no longer be reached over plain HTTP. */
    private String server(AiModel model) {
        servers.requireAllowed(model.baseUrl(), model.displayName());
        return model.baseUrl();
    }

    /** Ollama itself asks for no key; one in front of it (a reverse proxy) takes it as a bearer token. */
    private static Map<String, String> bearer(@Nullable String apiKey) {
        return apiKey == null ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);
    }

    /**
     * A redirect is never followed: the server's key and the company's data go to the address the admin entered and
     * nowhere else.
     */
    private static JdkHttpClientBuilder withoutRedirects() {
        return new JdkHttpClientBuilder().httpClientBuilder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER));
    }

    private static int contextLength(AiModel model) {
        return model.contextLength() != null ? model.contextLength() : DEFAULT_CONTEXT_LENGTH;
    }

    /**
     * The request; toolsCallable false lists the tools but lets none be called. Claude refuses a history with tool calls
     * without the tools' definitions ("must define tools"), so the providers' own models and Bedrock get them with tool
     * choice NONE (langchain4j's Bedrock client leaves NONE out - a call in that round is not run by the agent loop);
     * another model on an own server gets none - langchain4j's Ollama client refuses a tool choice, and servers differ
     * in what they take.
     */
    static ChatRequest request(AiModel model, List<ChatMessage> messages, List<ToolSpecification> tools,
                               boolean toolsCallable) {
        return request(model, messages, tools, toolsCallable, null);
    }

    /**
     * As {@link #request(AiModel, List, List, boolean)}; with a cache key (the agent loop's rounds) Anthropic caches
     * the conversation - its breakpoint moves with it by itself -, Bedrock up to the question (tools, instructions,
     * history and question: what every round shares), and OpenAI gets the key. Gemini, Ollama and OpenAI-compatible
     * servers cache by themselves.
     */
    static ChatRequest request(AiModel model, List<ChatMessage> messages, List<ToolSpecification> tools,
                               boolean toolsCallable, @Nullable String cacheKey) {
        DefaultChatRequestParameters.Builder<?> parameters = cacheKey == null ? ChatRequestParameters.builder()
                : switch (model.provider()) {
                    case ANTHROPIC -> AnthropicChatRequestParameters.builder().cacheAutomatically(true);
                    case OPENAI -> OpenAiChatRequestParameters.builder().promptCacheKey(cacheKey);
                    case BEDROCK -> BedrockChatRequestParameters.builder()
                            .promptCaching(BedrockCachePointPlacement.AFTER_LAST_USER_MESSAGE);
                    default -> ChatRequestParameters.builder();
                };
        if (!tools.isEmpty() && toolsCallable) {
            parameters.toolSpecifications(tools);
        } else if (!tools.isEmpty() && model.provider().needsToolsWithToolCalls()) {
            parameters.toolSpecifications(tools).toolChoice(ToolChoice.NONE);
        }
        return ChatRequest.builder().messages(messages).parameters(parameters.build()).build();
    }

    /**
     * The failure as the user may read it. For a model on an own server it names the model, never the server's address:
     * that is for the admin, who finds it in the log.
     */
    private static ServiceException providerFailure(AiModel model, RuntimeException e) {
        if (e instanceof ServiceException refused) {
            return refused;
        }
        // Never the provider's answer itself: a server may repeat the request in it - the company's data.
        LOG.warn("{} model {} failed: {}{}{}", model.provider(), model.modelName(), e.getClass().getSimpleName(),
                httpStatus(e).map(status -> " (HTTP " + status + ")").orElse(""), awsErrorCode(e));
        String name = name(model);
        Map<String, String> named = Map.of("name", name);
        if (causedBy(e, SSLException.class)) {
            return gateway("provider.certificateUntrusted",
                    name + ": the server's certificate is not trusted - ask the admin", named);
        }
        if (causedBy(e, ConnectException.class) || causedBy(e, UnknownHostException.class)
                || causedBy(e, HttpConnectTimeoutException.class)) {
            return gateway("provider.unreachable", name + " is not reachable - please try again later", named);
        }
        return switch (e) {
            case AuthenticationException _ when model.provider().hasOwnServer() ->
                    gateway("provider.keyRefusedOwnServer", name + " refused the API key - ask the admin", named);
            case AuthenticationException _ ->
                    gateway("provider.keyRefused", name + " refused the API key - check it under API-Keys", named);
            case ModelNotFoundException _ ->
                    gateway("provider.modelUnknown", name + " does not know the model " + model.modelName(),
                            Map.of("name", name, "model", model.modelName()));
            case RateLimitException _ ->
                    gateway("provider.rateLimited", name + " refused the request: rate limit or quota reached", named);
            case InvalidRequestException _ when isContextTooLong(e) ->
                    gateway("provider.contextTooLong", "The conversation is too long for " + name
                            + " - please start a new one", named);
            default -> gateway("provider.failed", name + " failed - please try again", named);
        };
    }

    private static ServiceException gateway(String code, String message, Map<String, String> params) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, code, message, params);
    }

    /** The provider's name - or for a model on an own server its display name. */
    private static String name(AiModel model) {
        return model.provider().hasOwnServer() ? model.displayName() : model.provider().name();
    }

    /** OpenAI says "context_length_exceeded", vLLM and others "maximum context length". */
    /** OpenAI's and Ollama's wording, Anthropic's, and Bedrock's ("Input is too long for requested model"). */
    private static boolean isContextTooLong(RuntimeException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("context_length_exceeded") || message.contains("context length")
                || message.contains("context window") || message.contains("too long");
    }

    private static Optional<Integer> httpStatus(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpException http) {
                return Optional.of(http.statusCode());
            }
            if (cause instanceof SdkServiceException aws) {
                return Optional.of(aws.statusCode());
            }
        }
        return Optional.empty();
    }

    /**
     * Bedrock's error code, e.g. " AccessDeniedException" (the model not enabled for the account) or
     * " UnrecognizedClientException" (an unknown access key) - for the admin's log; never AWS's message.
     */
    private static String awsErrorCode(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AwsServiceException aws && aws.awsErrorDetails() != null
                    && aws.awsErrorDetails().errorCode() != null) {
                return " " + aws.awsErrorDetails().errorCode();
            }
        }
        return "";
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
