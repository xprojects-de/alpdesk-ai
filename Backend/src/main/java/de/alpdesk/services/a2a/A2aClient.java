package de.alpdesk.services.a2a;

import de.alpdesk.entities.mcp.McpConnection;
import de.alpdesk.services.chat.ChatService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.mcp.Credential;
import de.alpdesk.services.oauth.OAuthClient;
import de.alpdesk.services.mcp.McpAddresses;
import de.alpdesk.services.mcp.McpClient;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientBuilder;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.http.A2ACardResolver;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.A2AClientError;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AuthorizationCodeOAuthFlow;
import org.a2aproject.sdk.spec.OAuth2SecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.A2AClientHTTPError;
import org.a2aproject.sdk.spec.A2AClientJSONError;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Talks to a tenant's A2A agent (docs/architecture/a2a.md "The client") with the official A2A SDK's client over the
 * JSON-RPC binding - the SDK writes and reads every byte of A2A, {@link A2aHttpClient} carries it under the rules of a
 * connected software's address. A client per call, nothing kept between calls: a connection's key may change.
 * <p>
 * The agent is called at the address the tenant admin entered, never at another the card names: the card's first
 * JSON-RPC interface lends its tenant and version, the address stays the connection's - the key goes where the admin
 * pointed it, nowhere else.
 */
@Singleton
public class A2aClient {

    /** Our extension's key in a message's metadata, and its name in the request (docs/architecture/a2a.md). */
    public static final String ASKER_EXTENSION = "urn:alpdesk-ai:a2a:asker:1";
    /** How often a task is asked for while it runs. */
    static final Duration TASK_POLL = Duration.ofSeconds(2);
    /** A failure text of the agent at most, in our error. */
    static final int MAX_ERROR_TEXT = 500;
    static final String FILES_TOO_LARGE = "left out - the files of one answer may have 8 MB together";
    private static final String JSONRPC = "JSONRPC";
    private static final String KEY_REFUSED = "mcpServer.keyRefused";
    static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(10);

    private final McpAddresses addresses;
    private final Duration timeout;
    private final HttpClient http;

    public A2aClient(McpAddresses addresses, @Value("${app.ai.timeout}") Duration timeout) {
        this.addresses = addresses;
        this.timeout = timeout;
        // Connecting is quick or fails - as McpClient's; the answer may take app.ai.timeout.
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** A skill of the card: its id, name and description. */
    public record Skill(String id, String name, String description) {
    }

    /**
     * What the card says: the agent's name and description, its skills - the extended card's when it gives one - and
     * the OAuth sign-in its public card asks for (null: none).
     */
    public record Card(String name, String description, List<Skill> skills, @Nullable SignInOffer signIn) {
    }

    /**
     * The OAuth 2.0 scheme the card's security requirements name (docs/architecture/a2a.md "The connection"): its
     * metadata URL, or the authorization code flow's URLs; authorizationCode false when it offers no such flow.
     */
    public record SignInOffer(@Nullable String metadataUrl, @Nullable String authorizationUrl, @Nullable String tokenUrl,
                              @Nullable String scopes, boolean authorizationCode) {
    }

    /**
     * Reads the agent's card - the public one at the address's origin (a signed-in connection's without its token: the
     * card is public), then the extended one with the key or token when the card offers it (a failing extended card
     * leaves the public one). 400 mcpConnection.unreachable when it cannot be read, .cardInvalid when it is no card,
     * .unsupportedBinding without a JSON-RPC interface.
     */
    public Card card(McpConnection connection, Credential credential) {
        A2aHttpClient open = adapter(connection, credential.signedIn() ? null : token(credential));
        AgentCard publicCard = publicCard(connection, open);
        SignInOffer signIn = signInOffer(publicCard);
        AgentCard card = callCard(connection, publicCard);
        if (card.capabilities() != null && card.capabilities().extendedAgentCard()) {
            AgentCard shown = card;
            try {
                card = withToken(connection, credential, token -> extendedCard(connection, shown, token));
            } catch (RuntimeException e) {
                // The public card is enough to work with - an agent whose extended card fails still answers.
            }
        }
        List<Skill> skills = new ArrayList<>();
        for (AgentSkill skill : card.skills()) {
            skills.add(new Skill(skill.id(), skill.name(), skill.description()));
        }
        return new Card(card.name(), card.description(), skills, signIn);
    }

    private AgentCard extendedCard(McpConnection connection, AgentCard card, @Nullable String token) {
        try (Client client = client(card, adapter(connection, token))) {
            return callCard(connection, client.getExtendedAgentCard(callContext()));
        } catch (RuntimeException e) {
            throw callFailure(connection, e);
        }
    }

    /** The first OAuth 2.0 scheme the card's security requirements name - null when they name none. */
    static @Nullable SignInOffer signInOffer(AgentCard card) {
        if (card.securityRequirements() == null || card.securitySchemes() == null) {
            return null;
        }
        for (SecurityRequirement requirement : card.securityRequirements()) {
            for (Map.Entry<String, List<String>> named : requirement.schemes().entrySet()) {
                if (card.securitySchemes().get(named.getKey()) instanceof OAuth2SecurityScheme oauth) {
                    AuthorizationCodeOAuthFlow flow = oauth.flows() == null ? null : oauth.flows().authorizationCode();
                    List<String> scopes = named.getValue() != null && !named.getValue().isEmpty() ? named.getValue()
                            : flow != null && flow.scopes() != null ? List.copyOf(flow.scopes().keySet()) : List.of();
                    return new SignInOffer(oauth.oauth2MetadataUrl(), flow == null ? null : flow.authorizationUrl(),
                            flow == null ? null : flow.tokenUrl(), scopes.isEmpty() ? null : String.join(" ", scopes),
                            flow != null);
                }
            }
        }
        return null;
    }

    /**
     * A call with the connection's key or token; a signed-in connection whose token is refused renews it once and the
     * call is repeated once - refused again, it needs a sign-in. A stream already running is never repeated: a refusal
     * comes before its first event.
     */
    private <T> T withToken(McpConnection connection, Credential credential, Function<String, T> call) {
        String token = token(credential);
        try {
            return call.apply(token);
        } catch (ServiceException e) {
            if (!credential.signedIn() || !KEY_REFUSED.equals(e.code())) {
                throw e;
            }
            if (e instanceof Forbidden) {
                // More rights wanted (step-up) - a new token of the same sign-in brings none.
                throw OAuthClient.signInNeeded(connection.name());
            }
        }
        try {
            return call.apply(credential.renew().apply(token));
        } catch (ServiceException e) {
            if (!KEY_REFUSED.equals(e.code())) {
                throw e;
            }
            if (!(e instanceof Forbidden)) {
                credential.signInLost().run();
            }
            throw OAuthClient.signInNeeded(connection.name());
        }
    }

    /** The agent answered 403 - "refused our key" to a key connection; a signed-in one is not renewed for it. */
    private static final class Forbidden extends ServiceException {

        Forbidden(McpConnection connection) {
            super(HttpStatus.BAD_GATEWAY, KEY_REFUSED, connection.name() + " refused our key - check it in the connection",
                    Map.of("name", connection.name()));
        }
    }

    private static @Nullable String token(Credential credential) {
        return credential.token() == null ? null : credential.token().get();
    }

    /**
     * Asks the agent once - the tool way (package A): a blocking SendMessage with who asks in our extension's
     * metadata (AgentAnswers.asker - its skills the one the model named). A Message is the answer; a Task is asked for
     * every {@link #TASK_POLL} until it ends or app.ai.timeout passed (a tool error naming it then). A question back
     * (input-required) is the result's text, not an error. The answer's text is cut at McpClient.MAX_RESULT_TEXT, its
     * files kept within ChatService.MAX_DELIVERED_BYTES.
     */
    public McpClient.CallResult ask(McpConnection connection, Credential credential, String question,
                                    Map<String, Object> asker) {
        Reply reply = send(connection, credential, List.of(new TextPart(question)), asker, timeout);
        if (reply.asksBack()) {
            return new McpClient.CallResult("The agent asks back: " + reply.result().text(), false, List.of(), false);
        }
        return reply.result();
    }

    /** The agent's answer, and whether it is a question back (input-required) rather than an answer. */
    public record Reply(McpClient.CallResult result, boolean asksBack) {
    }

    /**
     * Sends one message with these parts and our extension's metadata, blocking: a Message is the answer, a Task is asked
     * for every {@link #TASK_POLL} until it ends or the time passed (502 a2a.taskTimeout) - its artifacts the answer, a
     * question back its status message. A failed task is an error result.
     */
    public Reply send(McpConnection connection, Credential credential, List<Part<?>> parts, Map<String, Object> asker,
                      Duration waitAtMost) {
        Sent sent = withToken(connection, credential, token -> sendOnce(connection, token, parts, asker, waitAtMost));
        if (sent.reply() != null) {
            return sent.reply();
        }
        // The task is asked for on its own: a token refused while it runs is renewed - the message never goes twice.
        return ofTask(connection, withToken(connection, credential,
                token -> awaitEnd(connection, sent.card(), token, sent.task(), waitAtMost)));
    }

    /** What the message gave: the answer - or the card and the task to ask for until it ends. */
    private record Sent(@Nullable Reply reply, @Nullable AgentCard card, @Nullable Task task) {
    }

    private Sent sendOnce(McpConnection connection, @Nullable String key, List<Part<?>> parts, Map<String, Object> asker,
                          Duration waitAtMost) {
        // A blocking SendMessage sends its headers only with the answer: the request waits as long as the caller.
        A2aHttpClient adapter = adapter(connection, key, waitAtMost);
        AgentCard card = callCard(connection, publicCard(connection, adapter));
        Message message = message(parts, asker, null, null);
        AtomicReference<ClientEvent> answer = new AtomicReference<>();
        try (Client client = client(card, adapter)) {
            // Not streamed: the SDK hands the one answer to the consumer on this thread.
            client.sendMessage(message, List.of((event, agentCard) -> answer.set(event)), null, callContext());
            ClientEvent event = answer.get();
            if (event instanceof MessageEvent messageEvent) {
                return new Sent(new Reply(result(connection, messageEvent.getMessage().parts(), false), false), null, null);
            }
            Task task = event instanceof TaskEvent taskEvent ? taskEvent.getTask()
                    : event instanceof TaskUpdateEvent update ? update.getTask() : null;
            if (task == null) {
                throw failed(connection, "answered with nothing");
            }
            return new Sent(null, card, task);
        } catch (RuntimeException e) {
            throw callFailure(connection, e);
        }
    }

    /** A streamed call under way: its client (closed when the answer ended) and the adapter that cancels its stream. */
    public record Streamed(Client client, A2aHttpClient adapter, McpConnection connection, Credential credential) {

        /** Ends the stream - its consumers hear nothing more. */
        public void close() {
            adapter.cancel();
            client.close();
        }
    }

    /**
     * Sends the message as SendStreamingMessage (SendMessage when the card streams nothing) and returns at once: the
     * SDK hands every event to {@code events} and the end to {@code ended} (null: the stream ended normally) - on the
     * HTTP client's thread, not this one. A card refused is thrown here (renewed once by a signed-in connection); the
     * message refused comes as the stream's end ({@link #unauthorized}) - the caller renews on its own thread.
     * waitAtMost: how long the caller waits for the answer - for a card that streams nothing, whose SendMessage is
     * answered on this thread with the whole answer.
     */
    public Streamed send(McpConnection connection, Credential credential, Message message,
                         BiConsumer<ClientEvent, AgentCard> events, Consumer<Throwable> ended, Duration waitAtMost) {
        return withToken(connection, credential,
                token -> send(connection, credential, token, message, events, ended, waitAtMost));
    }

    private Streamed send(McpConnection connection, Credential credential, @Nullable String key, Message message,
                          BiConsumer<ClientEvent, AgentCard> events, Consumer<Throwable> ended, Duration waitAtMost) {
        try {
            AgentCard card = callCard(connection, publicCard(connection, adapter(connection, key)));
            // A stream's request is answered at once and its events take their time; a card that streams nothing
            // answers its SendMessage only with the whole answer - that one request waits as long as the caller.
            A2aHttpClient adapter = card.capabilities().streaming() ? adapter(connection, key)
                    : adapter(connection, key, waitAtMost);
            Client client = clientBuilder(card, adapter, true)
                    .addConsumer(events)
                    .streamingErrorHandler(ended)
                    .build();
            client.sendMessage(message, callContext());
            if (!card.capabilities().streaming()) {
                // Answered on this thread already: the end is now.
                ended.accept(null);
            }
            return new Streamed(client, adapter, connection, credential);
        } catch (RuntimeException e) {
            throw callFailure(connection, e);
        }
    }

    /** Asks the agent to cancel its task - best effort, a failure is the caller's to log. */
    public void cancelTask(McpConnection connection, Credential credential, String taskId) {
        withToken(connection, credential, token -> {
            A2aHttpClient adapter = adapter(connection, token);
            try (Client client = client(callCard(connection, publicCard(connection, adapter)), adapter)) {
                client.cancelTask(new CancelTaskParams(taskId), callContext());
                return null;
            } catch (RuntimeException e) {
                throw callFailure(connection, e);
            }
        });
    }

    private Task awaitEnd(McpConnection connection, AgentCard card, @Nullable String key, Task task,
                          Duration waitAtMost) {
        long until = System.nanoTime() + waitAtMost.toNanos();
        Task current = task;
        try (Client client = client(card, adapter(connection, key, waitAtMost))) {
            while (!current.status().state().isFinal() && !current.status().state().isInterrupted()) {
                if (System.nanoTime() > until) {
                    throw new ServiceException(HttpStatus.BAD_GATEWAY, "a2a.taskTimeout", connection.name()
                            + " did not finish its task " + current.id() + " in time",
                            Map.of("name", connection.name(), "task", current.id()));
                }
                pause();
                current = client.getTask(new TaskQueryParams(current.id(), null, null), callContext());
            }
            return current;
        } catch (RuntimeException e) {
            throw callFailure(connection, e);
        }
    }

    private Reply ofTask(McpConnection connection, Task task) {
        TaskState state = task.status().state();
        String statusText = task.status().message() == null ? "" : text(task.status().message().parts());
        return switch (state) {
            case TASK_STATE_COMPLETED -> new Reply(result(connection, artifactParts(task), false), false);
            case TASK_STATE_INPUT_REQUIRED, TASK_STATE_AUTH_REQUIRED -> new Reply(new McpClient.CallResult(
                    cut(statusText, McpClient.MAX_RESULT_TEXT), false, List.of(), false), true);
            default -> new Reply(new McpClient.CallResult("The agent's task ended " + stateName(state)
                    + (statusText.isBlank() ? "" : ": " + cut(statusText, MAX_ERROR_TEXT)), true, List.of(), false),
                    false);
        };
    }

    /** "TASK_STATE_FAILED" - "failed". */
    static String stateName(TaskState state) {
        return state.name().replace("TASK_STATE_", "").toLowerCase().replace('_', '-');
    }

    /**
     * The parts of the task's artifacts - the text parts of one artifact joined into one: an answer streamed into an
     * artifact arrives as pieces appended to it, a word or a line each.
     */
    static List<Part<?>> artifactParts(Task task) {
        List<Part<?>> parts = new ArrayList<>();
        if (task.artifacts() == null) {
            return parts;
        }
        for (Artifact artifact : task.artifacts()) {
            StringBuilder text = new StringBuilder();
            for (Part<?> part : artifact.parts()) {
                if (part instanceof TextPart textPart) {
                    text.append(textPart.text());
                } else {
                    parts.add(part);
                }
            }
            if (!text.isEmpty()) {
                parts.add(new TextPart(text.toString()));
            }
        }
        return parts;
    }

    /**
     * The parts as a tool result: the texts joined, data as its JSON, files with bytes returned within
     * {@link ChatService#MAX_DELIVERED_BYTES} together - a file beyond it, or one given as a link, named instead.
     */
    McpClient.CallResult result(McpConnection connection, List<Part<?>> parts, boolean error) {
        StringBuilder text = new StringBuilder();
        List<McpClient.ReturnedFile> files = new ArrayList<>();
        long delivered = 0;
        for (Part<?> part : parts) {
            switch (part) {
                case TextPart textPart -> text.append(textPart.text()).append("\n");
                case DataPart data -> text.append(json(data.data())).append("\n");
                case FilePart file when file.file() instanceof FileWithBytes bytes -> {
                    McpClient.ReturnedFile returned = returnedFile(bytes);
                    if (returned == null) {
                        text.append("[file \"").append(bytes.name()).append("\" left out - its content is no valid "
                                + "base64]\n");
                    } else if (delivered + returned.content().length > ChatService.MAX_DELIVERED_BYTES) {
                        text.append("[file \"").append(returned.name()).append("\" ").append(FILES_TOO_LARGE)
                                .append("]\n");
                    } else {
                        delivered += returned.content().length;
                        files.add(returned);
                    }
                }
                case FilePart file when file.file() instanceof FileWithUri link -> text.append("[file \"")
                        .append(link.name()).append("\" given as a link - not fetched]\n");
                default -> text.append("[a part left out]\n");
            }
        }
        String answer = text.toString().strip();
        boolean cutOff = answer.length() > McpClient.MAX_RESULT_TEXT;
        if (cutOff) {
            answer = answer.substring(0, McpClient.MAX_RESULT_TEXT) + "\n[cut off - the answer was longer]";
        }
        if (answer.isEmpty() && files.isEmpty()) {
            answer = "[" + connection.name() + " answered without a text]";
        }
        return new McpClient.CallResult(answer, error, files, cutOff);
    }

    static String text(List<Part<?>> parts) {
        StringBuilder text = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart) {
                text.append(text.isEmpty() ? "" : "\n").append(textPart.text());
            }
        }
        return text.toString().strip();
    }

    /**
     * The adapter for push notifications: no key of ours - the client's own token goes as its header. A webhook answers
     * at once or not at all: the SDK waits for each delivery on a shared pool.
     */
    A2aHttpClient pushClient() {
        return new A2aHttpClient(http, addresses, "webhook", "https://webhook.invalid", null, WEBHOOK_TIMEOUT);
    }

    A2aHttpClient adapter(McpConnection connection, @Nullable String key) {
        return adapter(connection, key, timeout);
    }

    private A2aHttpClient adapter(McpConnection connection, @Nullable String key, Duration requestTimeout) {
        return new A2aHttpClient(http, addresses, connection.name(), connection.baseUrl(), key, requestTimeout);
    }

    /** The card at the address's origin - the SDK's resolver, through our adapter. */
    private AgentCard publicCard(McpConnection connection, A2aHttpClient adapter) {
        try {
            return A2ACardResolver.builder().httpClient(adapter).baseUrl(origin(connection.baseUrl())).build()
                    .getAgentCard();
        } catch (A2AClientJSONError e) {
            throw ServiceException.badRequest("mcpConnection.cardInvalid", connection.name() + " offers no valid "
                    + "agent card: " + cut(String.valueOf(e.getMessage()), MAX_ERROR_TEXT), Map.of("name", connection.name()));
        } catch (A2AClientError e) {
            if (keyLeak(e)) {
                throw keyLeaks(connection);
            }
            throw ServiceException.badRequest("mcpConnection.unreachable", connection.name() + " could not be "
                    + "reached for its agent card", Map.of("name", connection.name()));
        } catch (A2aHttpClient.KeyLeak e) {
            throw keyLeaks(connection);
        }
    }

    /** The card as we call it: its first JSON-RPC interface, at the connection's address. */
    private static AgentCard callCard(McpConnection connection, AgentCard card) {
        AgentInterface jsonRpc = card.supportedInterfaces().stream()
                .filter(candidate -> JSONRPC.equalsIgnoreCase(candidate.protocolBinding()))
                .findFirst()
                .orElseThrow(() -> ServiceException.badRequest("mcpConnection.unsupportedBinding", connection.name()
                        + " offers no JSON-RPC interface - the only binding we speak", Map.of("name", connection.name())));
        return AgentCard.builder(card)
                .supportedInterfaces(List.of(new AgentInterface(JSONRPC, connection.baseUrl(), jsonRpc.tenant(),
                        jsonRpc.protocolVersion())))
                .build();
    }

    Client client(AgentCard card, A2aHttpClient adapter) throws A2AClientException {
        return clientBuilder(card, adapter, false).build();
    }

    /** The SDK's client over JSON-RPC through our adapter - a redirect is never followed. */
    private static ClientBuilder clientBuilder(AgentCard card, A2aHttpClient adapter, boolean streaming) {
        return Client.builder(card)
                .withTransport(JSONRPCTransport.class,
                        new JSONRPCTransportConfigBuilder().httpClient(adapter).followRedirects(false))
                .clientConfig(ClientConfig.builder().setStreaming(streaming).build());
    }

    /** One message to the agent: the parts, our extension's metadata and - going on with it - its context and task. */
    static Message message(List<Part<?>> parts, Map<String, Object> asker, @Nullable String contextId,
                           @Nullable String taskId) {
        return Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(parts)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .taskId(taskId)
                .metadata(Map.of(ASKER_EXTENSION, asker))
                .extensions(List.of(ASKER_EXTENSION))
                .build();
    }

    /** Our extension named in the header, as A2A activates an extension. */
    static ClientCallContext callContext() {
        return new ClientCallContext(new HashMap<>(), new HashMap<>(Map.of(A2AHeaders.A2A_EXTENSIONS, ASKER_EXTENSION)));
    }

    /**
     * What went wrong at a call, in words for the model and the user - never the agent's address or our key. The
     * agent's own error message is passed on (cut), as a software's is.
     */
    ServiceException callFailure(McpConnection connection, Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            switch (cause) {
                case ServiceException service -> {
                    return service;
                }
                case A2aHttpClient.KeyLeak _ -> {
                    return keyLeaks(connection);
                }
                case A2AClientHTTPError http when http.getCode() == 403 -> {
                    return new Forbidden(connection);
                }
                case A2AClientHTTPError http when http.getCode() == 401 -> {
                    return gateway("mcpServer.keyRefused", connection, "refused our key - check it in the connection");
                }
                case HttpConnectTimeoutException _, ConnectException _ -> {
                    return gateway("mcpServer.unreachable", connection, "is not reachable");
                }
                case HttpTimeoutException _ -> {
                    return gateway("mcpServer.timeout", connection, "did not answer in time");
                }
                case A2AError error -> {
                    String text = cut(String.valueOf(error.getMessage()), MAX_ERROR_TEXT);
                    return new ServiceException(HttpStatus.BAD_GATEWAY, "mcpServer.error", connection.name()
                            + " answered with an error: " + text, Map.of("name", connection.name(), "error", text));
                }
                default -> {
                }
            }
        }
        return failed(connection, "could not be asked");
    }

    /** Whether the agent answered 401 - our key or token refused. */
    public static boolean unauthorized(@Nullable Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof A2AClientHTTPError http && http.getCode() == 401) {
                return true;
            }
        }
        return false;
    }

    private static boolean keyLeak(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof A2aHttpClient.KeyLeak) {
                return true;
            }
        }
        return false;
    }

    private static ServiceException keyLeaks(McpConnection connection) {
        return ServiceException.badRequest("mcpConnection.keyLeaks", "The key of " + connection.name()
                + " would go to another host than the connection's - refused", Map.of("name", connection.name()));
    }

    private static ServiceException failed(McpConnection connection, String what) {
        return gateway("mcpServer.requestFailed", connection, what);
    }

    private static ServiceException gateway(String code, McpConnection connection, String what) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, code, connection.name() + " " + what,
                Map.of("name", connection.name()));
    }

    /** Through the SDK's JSON - as every byte of A2A. */
    private static String json(@Nullable Object data) {
        try {
            return JsonUtil.toJson(data);
        } catch (JsonProcessingException e) {
            return "[data left out - not readable]";
        }
    }

    /** A file of the agent's answer as we keep it - null when its content is no valid base64. */
    static McpClient.@Nullable ReturnedFile returnedFile(FileWithBytes file) {
        if (file.bytes() == null) {
            return null;
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(file.bytes());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String name = file.name() == null || file.name().isBlank() ? "datei" : file.name();
        return new McpClient.ReturnedFile(name, blankToNull(file.mimeType()), content);
    }

    static String origin(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) + " [cut off]" : text;
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private void pause() {
        try {
            Thread.sleep(TASK_POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(HttpStatus.SERVICE_UNAVAILABLE, "a2a.interrupted", "The call was stopped");
        }
    }
}
