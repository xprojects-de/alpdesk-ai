package de.alpdesk.services.a2a

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.alpdesk.controller.a2a.A2aController
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.NonStreamingJSONRPCRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest
import org.a2aproject.sdk.server.ServerCallContext
import org.a2aproject.sdk.server.agentexecution.AgentExecutor
import org.a2aproject.sdk.server.agentexecution.RequestContext
import org.a2aproject.sdk.server.auth.AuthenticatedUser
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider
import org.a2aproject.sdk.server.auth.TaskOperation
import org.a2aproject.sdk.server.events.InMemoryQueueManager
import org.a2aproject.sdk.server.events.MainEventBus
import org.a2aproject.sdk.server.events.MainEventBusProcessor
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler
import org.a2aproject.sdk.server.tasks.AgentEmitter
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore
import org.a2aproject.sdk.server.tasks.PushNotificationSender
import org.a2aproject.sdk.spec.A2AError
import org.a2aproject.sdk.spec.APIKeySecurityScheme
import org.a2aproject.sdk.spec.AgentCapabilities
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.AgentInterface
import org.a2aproject.sdk.spec.AgentSkill
import org.a2aproject.sdk.spec.AuthorizationCodeOAuthFlow
import org.a2aproject.sdk.spec.ClientCredentialsOAuthFlow
import org.a2aproject.sdk.spec.OAuth2SecurityScheme
import org.a2aproject.sdk.spec.OAuthFlows
import org.a2aproject.sdk.spec.SecurityRequirement
import org.a2aproject.sdk.spec.DataPart
import org.a2aproject.sdk.spec.FilePart
import org.a2aproject.sdk.spec.FileWithBytes
import org.a2aproject.sdk.spec.InternalError
import org.a2aproject.sdk.spec.Message
import org.a2aproject.sdk.spec.Part
import org.a2aproject.sdk.spec.TaskState
import org.a2aproject.sdk.spec.TextPart
import org.a2aproject.sdk.spec.TransportProtocol
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler

import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

import static org.a2aproject.sdk.server.ServerCallContext.TRANSPORT_KEY
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.HEADERS_KEY
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.METHOD_NAME_KEY
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.TENANT_KEY

/**
 * An A2A agent for the specs (like {@link de.alpdesk.services.mcp.FakeMcpSoftware}), built on the official SDK's server
 * core behind an embedded HTTP server: the card at /.well-known/agent-card.json, JSON-RPC at /a2a, a bearer key checked
 * if it has one. It answers a question with "Antwort von Fakeagent: <question>"; markers in the question choose:
 * {@code [[task]]} a Task whose answer comes as an appended artifact in pieces, {@code [[ask-back <text>]]} input-required
 * with the text (the next message to the task finishes it with "Fortgesetzt: <question>", or fails it with
 * {@code [[failed-task]]}), {@code [[file <name>]]} a file with bytes, {@code [[data]]} a data part, {@code [[fail]]} a
 * JSON-RPC error, {@code [[failed-task]]} a task that fails, {@code [[slow]]} an answer after {@code slowMillis},
 * {@code [[running]]} a task that ends after {@code slowMillis} ({@code [[working]]} the same with the working status
 * "Ich arbeite"), {@code [[echo-metadata]]} the metadata it got as the answer's text, {@code [[files]]} the names of the
 * files it got, {@code [[long]]} an answer of 3,000 characters, {@code [[empty]]} an answer of a blank,
 * {@code [[file-only <name>]]} an answer of a file alone.
 * It records every request: [method, authorization, version, extensions, metadata, text, taskId, contextId].
 */
class FakeA2aAgent {

    static final String ASKER = "urn:alpdesk-ai:a2a:asker:1"

    final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    final List<Map> received = new CopyOnWriteArrayList<>()
    volatile String key = null
    volatile long slowMillis = 3_000
    /** Whether the card says the agent streams - false: SendMessage answers in one piece, with the whole answer. */
    volatile boolean streams = true
    volatile String cardName = "Fakeagent"
    volatile String cardDescription = "Ein Agent der Specs, der Fragen beantwortet"
    volatile List<AgentSkill> skills = [
            AgentSkill.builder().id("rechnungen").name("Rechnungen").description("Prüft Rechnungen").tags(["finanzen"]).build(),
            AgentSkill.builder().id("urlaub").name("Urlaub").description("Plant Urlaube").tags(["personal"]).build()]
    /** The card's interfaces; null: JSON-RPC at /a2a. */
    volatile List<AgentInterface> interfaces = null
    /** The card's raw JSON instead of the SDK's; null: the SDK writes it. */
    volatile String rawCard = null
    /**
     * The authorization server whose tokens /a2a takes - the card names it as an OAuth 2.0 scheme (its metadata URL, or
     * with {@code cardWithFlowUrls} the authorization code flow's URLs; {@code clientCredentialsOnly}: no such flow).
     * The card stays public. Null: no OAuth.
     */
    volatile de.alpdesk.services.oauth.FakeAuthorizationServer oauth = null
    volatile boolean cardWithFlowUrls = false
    volatile boolean clientCredentialsOnly = false
    /** The next GetTask finds its token revoked first - a token refused while a task is asked for. */
    volatile boolean revokeOnNextPoll = false
    /** /a2a answers 403 to a valid token - the agent wants more rights (step-up). */
    volatile boolean forbidden = false
    /** The card names the key and the sign-in as alternatives: a request passes with either. */
    volatile boolean keyOrSignIn = false

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()
    private final ExecutorService requestThreads = Executors.newVirtualThreadPerTaskExecutor()
    private final MainEventBusProcessor processor
    private final JSONRPCHandler handler

    FakeA2aAgent() {
        def tasks = new InMemoryTaskStore()
        def bus = new MainEventBus()
        def queues = new InMemoryQueueManager(tasks, bus)
        processor = new MainEventBusProcessor(bus, tasks, PushNotificationSender.NOOP, queues)
        def requests = DefaultRequestHandler.builder()
                .agentExecutor(new Executor(this))
                .taskStore(tasks)
                .queueManager(queues)
                .pushConfigStore(new InMemoryPushNotificationConfigStore())
                .mainEventBusProcessor(processor)
                .executor(threads)
                .eventConsumerExecutor(threads)
                .authorizationProvider(new Anyone())
                .build()
        http.createContext("/.well-known/agent-card.json") { HttpExchange exchange -> serveCard(exchange) }
        http.createContext("/a2a") { HttpExchange exchange -> serve(exchange) }
        // A thread per request: a stream open must not hold up the next request (a CancelTask).
        http.executor = requestThreads
        http.start()
        handler = new JSONRPCHandler(card(), requests, threads)
    }

    String url() {
        "http://127.0.0.1:${http.address.port}/a2a"
    }

    /** Every feature starts an agent of its own: its SDK event thread left running would keep it and the spec alive. */
    void stop() {
        http.stop(0)
        requestThreads.shutdownNow()
        threads.shutdownNow()
        processor.stop()
    }

    AgentCard card() {
        def builder = AgentCard.builder()
                .name(cardName)
                .description(cardDescription)
                .version("1.0")
                .capabilities(AgentCapabilities.builder().streaming(streams).pushNotifications(false).extendedAgentCard(false).build())
                .defaultInputModes(["text/plain"])
                .defaultOutputModes(["text/plain"])
                .skills(skills)
                .supportedInterfaces(interfaces ?: [new AgentInterface("JSONRPC", url(), null, "1.0")])
        if (oauth && keyOrSignIn) {
            builder.securitySchemes(["key": new APIKeySecurityScheme(APIKeySecurityScheme.Location.HEADER, "Authorization", null),
                                     "oauth": oauthScheme()])
                    .securityRequirements([new SecurityRequirement(["key": []]), new SecurityRequirement(["oauth": ["read"]])])
        } else if (oauth) {
            builder.securitySchemes(["oauth": oauthScheme()])
                    .securityRequirements([new SecurityRequirement(["oauth": ["read"]])])
        }
        builder.build()
    }

    private OAuth2SecurityScheme oauthScheme() {
        def flows = clientCredentialsOnly
                ? new OAuthFlows(null, new ClientCredentialsOAuthFlow(null, [read: "Lesen"], oauth.origin() + "/token"), null)
                : new OAuthFlows(new AuthorizationCodeOAuthFlow(oauth.origin() + "/authorize", null, [read: "Lesen"],
                oauth.origin() + "/token", true), null, null)
        String metadataUrl = cardWithFlowUrls || clientCredentialsOnly ? null
                : oauth.origin() + "/.well-known/oauth-authorization-server" + oauth.issuerPath
        new OAuth2SecurityScheme(flows, "Sign in", metadataUrl)
    }

    List<Map> messages() {
        received.findAll { it.method in ["SendMessage", "SendStreamingMessage"] }
    }

    private void serveCard(HttpExchange exchange) {
        byte[] body = (rawCard ?: JsonUtil.toJson(card())).getBytes(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.length)
        exchange.responseBody.withCloseable { it.write(body) }
    }

    private void serve(HttpExchange exchange) {
        String authorization = exchange.requestHeaders.getFirst("Authorization")
        if (revokeOnNextPoll && oauth != null && exchange.requestURI.path == "/a2a" && authorization) {
            String body = new String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            if (body.contains('"GetTask"')) {
                revokeOnNextPoll = false
                oauth.revoke(authorization.substring(7))
            }
            exchange.setStreams(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), exchange.responseBody)
        }
        if (forbidden && exchange.requestURI.path == "/a2a") {
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
            return
        }
        boolean keyTaken = key != null && authorization == "Bearer " + key
        boolean tokenTaken = oauth != null && oauth.valid(authorization)
        boolean refused = keyOrSignIn ? !(keyTaken || tokenTaken)
                : (key != null && !keyTaken) || (oauth != null && !tokenTaken)
        if (refused) {
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
            return
        }
        String body = new String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)
        A2ARequest<?> request = JSONRPCUtils.parseRequestBody(body, "")
        record(exchange, request, authorization)
        def context = callContext(exchange, request)
        if (request instanceof NonStreamingJSONRPCRequest) {
            String answer = A2aController.serialize(A2aController.answer(handler, request as NonStreamingJSONRPCRequest, context))
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.length)
            exchange.responseBody.withCloseable { it.write(bytes) }
            return
        }
        Flow.Publisher<SendStreamingMessageResponse> events = request instanceof SendStreamingMessageRequest
                ? handler.onMessageSendStream(request as SendStreamingMessageRequest, context)
                : handler.onSubscribeToTask(request as SubscribeToTaskRequest, context)
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        def out = exchange.responseBody
        def done = new CountDownLatch(1)
        events.subscribe(new Flow.Subscriber<SendStreamingMessageResponse>() {
            Flow.Subscription subscription

            void onSubscribe(Flow.Subscription s) {
                subscription = s
                s.request(Long.MAX_VALUE)
            }

            void onNext(SendStreamingMessageResponse event) {
                try {
                    out.write(("data: " + A2aController.serialize(event) + "\n\n").getBytes(StandardCharsets.UTF_8))
                    out.flush()
                } catch (IOException gone) {
                    subscription.cancel()
                    done.countDown()
                }
            }

            void onError(Throwable failure) {
                try {
                    out.write(("data: " + A2aController.serialize(new A2AErrorResponse(request.id,
                            failure instanceof A2AError ? failure as A2AError : new InternalError(failure.message))) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8))
                } catch (IOException ignored) {
                }
                done.countDown()
            }

            void onComplete() {
                done.countDown()
            }
        })
        done.await(2, TimeUnit.MINUTES)
        try {
            out.close()
        } catch (IOException ignored) {
        }
    }

    private void record(HttpExchange exchange, A2ARequest<?> request, String authorization) {
        Map entry = [method       : request.method, authorization: authorization,
                     version      : exchange.requestHeaders.getFirst("A2A-Version"),
                     extensions   : exchange.requestHeaders.getFirst("A2A-Extensions")]
        def params = request.params
        if (params != null && params.hasProperty("message") && params.message != null) {
            Message message = params.message as Message
            entry.metadata = message.metadata()
            entry.text = textOf(message.parts())
            entry.parts = message.parts()
            entry.taskId = message.taskId()
            entry.contextId = message.contextId()
            entry.messageExtensions = message.extensions()
        } else if (params != null && params.hasProperty("id")) {
            entry.taskId = params.id
        }
        received << entry
    }

    private static ServerCallContext callContext(HttpExchange exchange, A2ARequest<?> request) {
        Map<String, Object> state = [:]
        state.put(HEADERS_KEY, [:])
        state.put(TENANT_KEY, "")
        state.put(TRANSPORT_KEY, TransportProtocol.JSONRPC)
        state.put(METHOD_NAME_KEY, request.method)
        new ServerCallContext(new AuthenticatedUser("spec"), state, [] as Set, exchange.requestHeaders.getFirst("A2A-Version"))
    }

    static String textOf(List<Part<?>> parts) {
        parts.findAll { it instanceof TextPart }.collect { (it as TextPart).text() }.join("\n")
    }

    /** Answers by the markers of the question. */
    static class Executor implements AgentExecutor {

        final FakeA2aAgent agent

        Executor(FakeA2aAgent agent) {
            this.agent = agent
        }

        @Override
        void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
            String question = textOf(context.message.parts())
            if (context.task != null && context.task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
                if (question.contains("[[failed-task]]")) {
                    emitter.fail(emitter.newAgentMessage([new TextPart("Die Buchung schlug fehl")] as List<Part<?>>, null))
                    return
                }
                emitter.startWork()
                emitter.addArtifact([new TextPart("Fortgesetzt: " + question)] as List<Part<?>>, "answer", "answer", [:])
                emitter.complete()
                return
            }
            if (question.contains("[[slow]]")) {
                Thread.sleep(agent.slowMillis)
            }
            if (question.contains("[[fail]]")) {
                throw new InternalError("Der Agent ist gestört")
            }
            def askBack = question =~ /\[\[ask-back ([^\]]+)\]\]/
            if (askBack.find()) {
                emitter.addTask(emitter.taskBuilder().status(new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_SUBMITTED)).build())
                // Final for the stream: input-required ends it, as A2A 1.0 says.
                emitter.requiresInput(emitter.newAgentMessage([new TextPart(askBack.group(1))] as List<Part<?>>, null), true)
                return
            }
            if (question.contains("[[failed-task]]")) {
                emitter.addTask(emitter.taskBuilder().status(new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_SUBMITTED)).build())
                emitter.fail(emitter.newAgentMessage([new TextPart("Die Buchung schlug fehl")] as List<Part<?>>, null))
                return
            }
            if (question.contains("[[running]]") || question.contains("[[working]]")) {
                // Still working when SendMessage answers: the client asks for the task until it ends.
                emitter.addTask(emitter.taskBuilder().status(new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_SUBMITTED)).build())
                if (question.contains("[[working]]")) {
                    emitter.startWork(emitter.newAgentMessage([new TextPart("Ich arbeite")] as List<Part<?>>, null))
                } else {
                    emitter.startWork()
                }
                Thread.start {
                    Thread.sleep(agent.slowMillis)
                    emitter.addArtifact([new TextPart("Fertig: " + question.replaceAll(/\[\[[^\]]*\]\]/, '').strip())] as List<Part<?>>,
                            "answer", "answer", [:])
                    emitter.complete()
                }
                return
            }
            List<Part<?>> parts = answer(question, context.message)
            if (!question.contains("[[task]]")) {
                emitter.sendMessage(parts)
                return
            }
            emitter.addTask(emitter.taskBuilder().status(new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_SUBMITTED)).build())
            emitter.startWork(emitter.newAgentMessage([new TextPart(question.contains("[[chatty]]") ? "x" * 2000
                    : "Ich suche")] as List<Part<?>>, null))
            String text = textOf(parts)
            String[] words = text.split(" ")
            for (int i = 0; i < words.length; i++) {
                emitter.addArtifact([new TextPart((i == 0 ? "" : " ") + words[i])] as List<Part<?>>, "answer", "answer",
                        [:], i > 0, i == words.length - 1)
                Thread.sleep(20)
            }
            def files = parts.findAll { it instanceof FilePart }
            if (files) {
                emitter.addArtifact(files, "files", "files", [:])
            }
            emitter.complete()
        }

        private List<Part<?>> answer(String question, Message message) {
            List<Part<?>> parts = []
            def fileOnly = question =~ /\[\[file-only ([^\]]+)\]\]/
            if (fileOnly.find()) {
                return [new FilePart(new FileWithBytes("text/plain", fileOnly.group(1),
                        "Inhalt von ${fileOnly.group(1)}".toString().getBytes(StandardCharsets.UTF_8)))] as List<Part<?>>
            }
            if (question.contains("[[empty]]")) {
                return [new TextPart(" ")] as List<Part<?>>
            }
            if (question.contains("[[echo-metadata]]")) {
                parts << new TextPart(JsonUtil.toJson(message.metadata()?.get(ASKER) ?: [:]))
            } else if (question.contains("[[files]]")) {
                def names = message.parts().findAll { it instanceof FilePart }.collect { ((it as FilePart).file() as FileWithBytes).name() }
                parts << new TextPart("Dateien: " + names.join(", ") + " | " + question.replaceAll(/\[\[[^\]]*\]\]/, '').strip())
            } else if (question.contains("[[long]]")) {
                parts << new TextPart("x" * 3000)
            } else {
                parts << new TextPart("Antwort von ${agent.cardName}: ${question.replaceAll(/\[\[[^\]]*\]\]/, '').strip()}".toString())
            }
            def file = question =~ /\[\[file ([^\]]+)\]\]/
            if (file.find()) {
                parts << new FilePart(new FileWithBytes("text/plain", file.group(1),
                        "Inhalt von ${file.group(1)}".toString().getBytes(StandardCharsets.UTF_8)))
            }
            if (question.contains("[[data]]")) {
                parts << new DataPart([betrag: 42, waehrung: "EUR"])
            }
            parts
        }

        @Override
        void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
            emitter.cancel()
        }
    }

    /** Every caller may read and write every task - the fake checks the key at the door only. */
    static class Anyone implements TaskAuthorizationProvider {

        boolean checkRead(ServerCallContext context, String taskId, TaskOperation operation) { true }

        boolean checkWrite(ServerCallContext context, String taskId, TaskOperation operation) { true }

        boolean checkCreate(ServerCallContext context, TaskOperation operation) { true }

        boolean isTaskRecorded(String taskId) { true }

        void recordOwnership(ServerCallContext context, String taskId, TaskOperation operation) {}
    }
}
