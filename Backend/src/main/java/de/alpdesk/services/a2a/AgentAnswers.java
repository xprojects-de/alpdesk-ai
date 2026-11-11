package de.alpdesk.services.a2a;

import de.alpdesk.entities.mcp.McpConnection;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.entities.tenant.TenantUser;
import de.alpdesk.entities.tenant.UserGroup;
import de.alpdesk.repositories.mcp.TenantMcpKeyRepository;
import de.alpdesk.repositories.tenant.TenantApiKeyRepository;
import de.alpdesk.repositories.tenant.TenantUserRepository;
import de.alpdesk.repositories.tenant.UserGroupRepository;
import de.alpdesk.services.tenant.UserGroupService;
import de.alpdesk.services.tools.ToolContext;
import de.alpdesk.services.chat.ChatService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.mcp.Credential;
import de.alpdesk.services.mcp.McpClient;
import de.alpdesk.services.mcp.McpConnectionService;
import de.alpdesk.services.oauth.OAuthClient;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A question of a tenant that answers through its A2A agent (docs/architecture/a2a.md "A tenant answering through its
 * agent") - the one class that maps the agent's events onto the chat's: a Message is the answer, a Task's artifacts are
 * its pieces, a working status a tool step ("the agent works"), input-required the agent's question back.
 * <p>
 * Threads: the SDK's client hands its events over on the HTTP client's thread. They only collect the answer and pass
 * pieces to the chat's listener (thread-safe sinks), never block, and touch no database; the asking thread waits for
 * the end with app.a2a.timeout and does everything after it - storing, the conversation's context, the log line. One
 * latch and the event collector's fields, published by the latch, are all they share.
 */
@Singleton
public class AgentAnswers {

    private static final Logger LOG = LoggerFactory.getLogger(AgentAnswers.class);

    private final A2aClient a2a;
    private final McpConnectionService connections;
    private final TenantUserRepository users;
    private final UserGroupService groups;
    private final UserGroupRepository groupRows;
    private final TenantApiKeyRepository apiKeys;
    private final TenantMcpKeyRepository mcpKeys;
    private final Duration timeout;
    private final int maxAnswerChars;
    /** The agent calls open per tenant - a question at /mcp or /a2a meanwhile may be the agent asking back (a loop). */
    private final Map<Long, AtomicInteger> open = new ConcurrentHashMap<>();

    public AgentAnswers(A2aClient a2a, McpConnectionService connections, TenantUserRepository users,
                        UserGroupService groups, UserGroupRepository groupRows, TenantApiKeyRepository apiKeys,
                        TenantMcpKeyRepository mcpKeys, @Value("${app.a2a.timeout}") Duration timeout,
                        @Value("${app.a2a.max-answer-chars}") int maxAnswerChars) {
        this.a2a = a2a;
        this.connections = connections;
        this.users = users;
        this.groups = groups;
        this.groupRows = groupRows;
        this.apiKeys = apiKeys;
        this.mcpKeys = mcpKeys;
        this.timeout = timeout;
        this.maxAnswerChars = maxAnswerChars;
    }

    /** A file sent with the question as a part with bytes. */
    public record OutgoingFile(String name, String mediaType, byte[] content) {
    }

    /**
     * What goes to the agent: the question, the files, our extension's metadata (the asker), and where the agent is
     * with the conversation - its context, and the task that waits for this answer (null for none). skillsToKeep: the
     * skills left out to store with the conversation; null: its choice stays.
     */
    public record Question(McpConnection agent, String text, List<OutgoingFile> files, Map<String, Object> asker,
                           @Nullable String contextId, @Nullable String taskId, Call call,
                           @Nullable List<String> skillsToKeep) {
    }

    /** The agent's answer: its text, its files, its context and - while it waits for the user - its task. */
    public record Result(String text, List<McpClient.ReturnedFile> files, @Nullable String contextId,
                         @Nullable String taskId, boolean waiting) {
    }

    /** Where a question's pieces go while the agent answers - called on the SDK's thread. */
    public record Listener(Consumer<String> onPart, Consumer<String> onWorking) {

        public static final Listener NONE = new Listener(_ -> {
        }, _ -> {
        });
    }

    /** A question under way - stopped by the user (a closed stream, a logout): the agent's task is cancelled. */
    public static final class Call {

        private volatile boolean stopped;
        private volatile boolean taken;
        private volatile @Nullable Runnable onStop;

        /**
         * Whether the agent took the question: an event of its answer came, it answered with an error of its own, or
         * we stopped it. A question it never got - out of reach, the key refused - leaves a task that waited as it was.
         */
        public boolean taken() {
            return taken;
        }

        /** Called where the stream closed (the server's event loop): the cancel at the agent runs on a thread of its own. */
        public void stop() {
            stopped = true;
            Runnable stop = onStop;
            if (stop != null) {
                Thread.ofVirtual().name("a2a-stop").start(stop);
            }
        }

        boolean stopped() {
            return stopped;
        }
    }

    /**
     * Who asks, as our extension urn:alpdesk-ai:a2a:asker:1 tells the agent (docs/architecture/a2a.md "Metadata"): the
     * tenant's name, the asker's language, the user - or the API key or MCP key by its name -, the names of its groups
     * and the proxy's groups, and the skills it may use. Names, never ids; nothing in it is a secret. A key has no
     * language: the tenant's search language stands for it.
     */
    public Map<String, Object> asker(Tenant tenant, ToolContext.Asker asker, @Nullable Long userId, @Nullable Long keyId,
                                     List<String> skills) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant", tenant.name());
        TenantUser user = userId == null ? null : users.findById(userId).orElse(null);
        metadata.put("language", user != null ? user.language() : tenant.searchLanguage().name().toLowerCase());
        if (user != null) {
            metadata.put("user", user.username());
        } else if (asker == ToolContext.Asker.CLIENT_SOFTWARE && keyId != null) {
            apiKeys.findById(keyId).ifPresent(key -> metadata.put("apiKey", key.name()));
        } else if (asker == ToolContext.Asker.MCP_SERVER && keyId != null) {
            mcpKeys.findById(keyId).ifPresent(key -> metadata.put("mcpKey", key.name()));
        }
        metadata.put("groups", user == null ? List.of() : groupNames(tenant.id(), user.id()));
        metadata.put("idmGroups", user == null ? List.of() : groups.wamGroupsOfUser(user.id()));
        metadata.put("skills", List.copyOf(skills));
        return metadata;
    }

    private List<String> groupNames(Long tenantId, Long userId) {
        Set<Long> memberOf = new HashSet<>(groups.groupsOfUser(userId));
        List<String> names = new ArrayList<>();
        for (UserGroup group : groupRows.findByTenantIdOrderByName(tenantId)) {
            if (memberOf.contains(group.id())) {
                names.add(group.name());
            }
        }
        return names;
    }

    /**
     * Asks the agent and waits for its answer on this thread - up to app.a2a.timeout. 502 a2a.agentFailed when the
     * agent cannot be asked or its task fails, 504 a2a.timeout when it takes longer, 409 answer.stopped when the user
     * stopped it (its task cancelled at the agent).
     */
    public Result ask(Long tenantId, Question question, Listener listener) {
        McpConnection agent = question.agent();
        long started = System.nanoTime();
        Credential credential = connections.credential(agent);
        Events events = new Events(agent, listener, question.call());
        AtomicInteger count = open.computeIfAbsent(tenantId, _ -> new AtomicInteger());
        count.incrementAndGet();
        try {
            events = answer(question, credential, events);
            if (events.tokenRefused() && credential.signedIn()) {
                // Refused before its stream began, the message never reached the agent: sent once more with a
                // renewed token - refused again, the sign-in is lost.
                renew(agent, credential);
                events = answer(question, credential, new Events(agent, listener, question.call()));
                if (events.tokenRefused()) {
                    credential.signInLost().run();
                    throw agentFailed(agent, OAuthClient.signInNeeded(agent.name()));
                }
            }
            Result result = events.result();
            log(tenantId, agent, started, events, null);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed(agent, "was interrupted", "interrupted");
        } catch (ServiceException e) {
            log(tenantId, agent, started, events, e);
            throw e;
        } finally {
            count.decrementAndGet();
        }
    }

    /** One message to the agent and the wait for its answer - the events as they ended. */
    private Events answer(Question question, Credential credential, Events events) throws InterruptedException {
        McpConnection agent = question.agent();
        A2aClient.Streamed streamed;
        try {
            streamed = a2a.send(agent, credential, message(question), events, events::ended, timeout);
        } catch (ServiceException refused) {
            // A card that streams nothing had us wait here for its whole answer - that wait's end is the agent's.
            throw "mcpServer.timeout".equals(refused.code()) ? tookTooLong(agent) : agentFailed(agent, refused);
        }
        try {
            question.call().onStop = () -> events.stop(streamed);
            if (question.call().stopped()) {
                // Stopped before we could be told: while the card was read - or, for a card that streams nothing,
                // while its whole answer was waited for. That answer is here already, and dropped.
                events.stoppedMeanwhile(streamed);
            }
            if (!events.done.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                events.stop(streamed);
                throw tookTooLong(agent);
            }
            return events;
        } finally {
            question.call().onStop = null;
            // Not the stream itself: an agent may take a client going away for a cancel of its task (the SDK's
            // server does) - a task waiting for the user's answer must stay. The agent ends its stream.
            streamed.client().close();
        }
    }

    private ServiceException tookTooLong(McpConnection agent) {
        return new ServiceException(HttpStatus.GATEWAY_TIMEOUT, "a2a.timeout", agent.name()
                + " did not answer within " + timeout.toMinutes() + " minutes",
                Map.of("name", agent.name(), "minutes", String.valueOf(timeout.toMinutes())));
    }

    /** A new token for the one the agent refused - on this thread, never the SDK's: it reads and writes the grant. */
    private static void renew(McpConnection agent, Credential credential) {
        try {
            credential.renew().apply(credential.token().get());
        } catch (ServiceException refused) {
            throw agentFailed(agent, refused);
        }
    }

    private static ServiceException agentFailed(McpConnection agent, ServiceException refused) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "a2a.agentFailed", refused.getMessage(),
                Map.of("name", agent.name(), "error", refused.getMessage()));
    }

    /**
     * Cancels the agent's task waiting in a deleted conversation - best effort: a failure is logged, the conversation
     * goes anyway.
     */
    public void cancelQuietly(Long tenantId, String taskId) {
        try {
            // Not once the admin took A2A away: such a tenant's agent hears nothing more from us.
            connections.answeringAgent(tenantId).filter(_ -> connections.a2aAllowed(tenantId)).ifPresent(agent ->
                    a2a.cancelTask(agent, connections.credential(agent), taskId));
        } catch (RuntimeException e) {
            LOG.warn("A2A task {} of tenant {} could not be cancelled: {}", taskId, tenantId, e.getMessage());
        }
    }

    /**
     * A question to a tenant answering through its agent, at its MCP server or its A2A server, while an agent call of
     * the tenant is open: possibly the agent asking its own tenant back - a loop that ends only at the timeouts. Warned,
     * not refused: someone else may ask at the same moment.
     */
    public void warnIfLoop(Long tenantId, String door) {
        AtomicInteger count = open.get(tenantId);
        if (count != null && count.get() > 0) {
            LOG.warn("Possible loop: tenant {} answering through its A2A agent was asked at {} while an agent call is "
                    + "open", tenantId, door);
        }
    }

    private Message message(Question question) {
        List<Part<?>> parts = new ArrayList<>();
        parts.add(new TextPart(question.text()));
        for (OutgoingFile file : question.files()) {
            parts.add(new FilePart(new FileWithBytes(file.mediaType(), file.name(), file.content())));
        }
        return A2aClient.message(parts, question.asker(), question.contextId(), question.taskId());
    }

    /** One line per call: the connection, the time, the end, the task - never the question or the answer. */
    private static void log(Long tenantId, McpConnection agent, long started, Events events,
                            @Nullable ServiceException failure) {
        long millis = (System.nanoTime() - started) / 1_000_000;
        if (failure == null) {
            LOG.info("A2A call of tenant {} to {} ended {} in {} ms (task {})", tenantId, agent.name(),
                    events.endState(), millis, events.taskId);
        } else {
            LOG.warn("A2A call of tenant {} to {} failed in {} ms (task {}): {}", tenantId, agent.name(), millis,
                    events.taskId, failure.code());
        }
    }

    /**
     * what: the sentence after the agent's name; detail: what the user is told went wrong (the agent's words) - both
     * cut like an error text: a failed task's status has no limit of its own.
     */
    private ServiceException failed(McpConnection agent, String what, String detail) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "a2a.agentFailed",
                agent.name() + " " + A2aClient.cut(what, A2aClient.MAX_ERROR_TEXT),
                Map.of("name", agent.name(), "error", A2aClient.cut(detail, A2aClient.MAX_ERROR_TEXT)));
    }

    /** The agent's events, collected on the SDK's thread; read by the asking thread once {@link #done} is down. */
    private final class Events implements BiConsumer<ClientEvent, AgentCard> {

        final CountDownLatch done = new CountDownLatch(1);
        private final McpConnection agent;
        private final Listener listener;
        private final Call call;
        private final StringBuilder text = new StringBuilder();
        private final List<McpClient.ReturnedFile> files = new ArrayList<>();
        private long fileBytes;
        private boolean cut;
        private volatile @Nullable String contextId;
        private volatile @Nullable String taskId;
        private volatile @Nullable TaskState state;
        private @Nullable String statusText;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean stopped;

        Events(McpConnection agent, Listener listener, Call call) {
            this.agent = agent;
            this.listener = listener;
            this.call = call;
        }

        @Override
        public void accept(ClientEvent event, AgentCard card) {
            call.taken = true;
            // Stopped, or the answer ended (a question back may leave the agent's stream open): nothing more is taken.
            if (stopped || done.getCount() == 0) {
                return;
            }
            switch (event) {
                case MessageEvent message -> {
                    contextId = message.getMessage().contextId();
                    take(message.getMessage().parts());
                    // A stream of a Message holds that one alone.
                    done.countDown();
                }
                case TaskEvent taskEvent -> task(taskEvent.getTask(), true);
                case TaskUpdateEvent update -> {
                    task(update.getTask(), false);
                    switch (update.getUpdateEvent()) {
                        case TaskStatusUpdateEvent status -> status(status.status().state(),
                                status.status().message() == null ? "" : A2aClient.text(status.status().message().parts()));
                        case TaskArtifactUpdateEvent artifact -> take(artifact.artifact().parts());
                        default -> {
                        }
                    }
                }
                default -> {
                }
            }
        }

        /** The task as it is now; its artifacts only when the agent answered with a whole task at once. */
        private void task(Task task, boolean whole) {
            taskId = task.id();
            contextId = task.contextId();
            if (whole) {
                // The artifacts before the status: a final status releases the asking thread, which reads the text.
                if (task.artifacts() != null && text.isEmpty() && files.isEmpty()) {
                    for (Artifact artifact : task.artifacts()) {
                        take(artifact.parts());
                    }
                }
                status(task.status().state(), task.status().message() == null ? ""
                        : A2aClient.text(task.status().message().parts()));
            }
        }

        /**
         * A final or interrupted state ends the answer here - an agent may keep its stream open after input-required
         * (the SDK's server does).
         */
        private void status(TaskState now, String message) {
            state = now;
            if (now == TaskState.TASK_STATE_WORKING && !message.isBlank()) {
                // A status is a line for the chat - an agent may send as many and as long as it likes.
                listener.onWorking().accept(A2aClient.cut(message, A2aClient.MAX_ERROR_TEXT));
            } else if (!message.isBlank()) {
                statusText = message;
            }
            if (now.isFinal() || now.isInterrupted()) {
                done.countDown();
            }
        }

        private void take(List<Part<?>> parts) {
            for (Part<?> part : parts) {
                if (part instanceof TextPart textPart) {
                    append(textPart.text());
                } else if (part instanceof FilePart file && file.file() instanceof FileWithBytes bytes) {
                    keep(bytes);
                }
            }
        }

        private void append(String piece) {
            if (cut || piece.isEmpty()) {
                return;
            }
            int room = maxAnswerChars - text.length();
            if (piece.length() > room) {
                cut = true;
                String rest = piece.substring(0, Math.max(0, room)) + "...";
                text.append(rest);
                listener.onPart().accept(rest);
                LOG.warn("The answer of {} was cut at {} characters", agent.name(), maxAnswerChars);
                return;
            }
            text.append(piece);
            listener.onPart().accept(piece);
        }

        private void keep(FileWithBytes file) {
            McpClient.ReturnedFile returned = A2aClient.returnedFile(file);
            if (returned == null) {
                return;
            }
            if (fileBytes + returned.content().length > ChatService.MAX_DELIVERED_BYTES) {
                append("\n[file \"" + returned.name() + "\" " + A2aClient.FILES_TOO_LARGE + "]");
                return;
            }
            fileBytes += returned.content().length;
            files.add(returned);
        }

        /** The agent refused the token before its stream began - nothing of an answer came, nothing was stopped. */
        boolean tokenRefused() {
            return !stopped && A2aClient.unauthorized(failure.get());
        }

        /** The end of the stream: null when it ended normally. */
        void ended(@Nullable Throwable error) {
            if (error != null) {
                failure.compareAndSet(null, error);
                // The agent's own error (a task it no longer knows, one that ended), not one of the way to it.
                if (agentsOwn(error)) {
                    call.taken = true;
                }
            }
            done.countDown();
        }

        /** A stop after the answer ended (the stream closed after "done") changes nothing - a waiting task stays. */
        void stop(A2aClient.Streamed streamed) {
            if (done.getCount() == 0) {
                return;
            }
            stopped = true;
            call.taken = true;
            failure.compareAndSet(null, new ServiceException(HttpStatus.CONFLICT, "answer.stopped",
                    "The answer was stopped"));
            streamed.close();
            String task = taskId;
            if (task != null && (state == null || !state.isFinal())) {
                try {
                    a2a.cancelTask(streamed.connection(), streamed.credential(), task);
                } catch (RuntimeException e) {
                    LOG.warn("A2A task {} of {} could not be cancelled: {}", task, agent.name(), e.getMessage());
                }
            }
            done.countDown();
        }

        /**
         * A stop that came before the asking thread could be told. An answer still under way is stopped like any
         * other; one that ended meanwhile (a card that streams nothing answers in one piece) is dropped - nothing is
         * left to cancel at the agent, and a task of it that waits for the user stays.
         */
        void stoppedMeanwhile(A2aClient.Streamed streamed) {
            if (done.getCount() > 0) {
                stop(streamed);
                return;
            }
            stopped = true;
            call.taken = true;
            failure.set(new ServiceException(HttpStatus.CONFLICT, "answer.stopped", "The answer was stopped"));
        }

        String endState() {
            TaskState now = state;
            return now == null ? "with a message" : A2aClient.stateName(now);
        }

        Result result() {
            Throwable error = failure.get();
            if (error instanceof ServiceException service) {
                throw service;
            }
            if (error != null) {
                throw failed(agent, "could not answer: " + reason(error), reason(error));
            }
            TaskState now = state;
            if (now == TaskState.TASK_STATE_FAILED || now == TaskState.TASK_STATE_REJECTED
                    || now == TaskState.TASK_STATE_CANCELED) {
                String detail = statusText == null ? A2aClient.stateName(now) : statusText;
                throw failed(agent, "ended its task " + A2aClient.stateName(now)
                        + (statusText == null ? "" : ": " + statusText), detail);
            }
            boolean waiting = now == TaskState.TASK_STATE_INPUT_REQUIRED || now == TaskState.TASK_STATE_AUTH_REQUIRED;
            if (waiting && statusText != null) {
                append((text.isEmpty() ? "" : "\n\n") + statusText);
            }
            return new Result(text.toString(), List.copyOf(files), contextId, waiting ? taskId : null, waiting);
        }

        /** The agent's own error message (a JSON-RPC error), cut - never our key or its address. */
        private static boolean agentsOwn(Throwable error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof A2AError) {
                    return true;
                }
            }
            return false;
        }

        private static String reason(Throwable error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof A2AError a2aError && a2aError.getMessage() != null) {
                    return A2aClient.cut(a2aError.getMessage(), A2aClient.MAX_ERROR_TEXT);
                }
            }
            return "no answer";
        }
    }
}
