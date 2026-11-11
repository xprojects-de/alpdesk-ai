package de.alpdesk.controller.a2a

import de.alpdesk.entities.a2a.A2aTaskIndex
import de.alpdesk.repositories.a2a.A2aTaskIndexRepository
import de.alpdesk.repositories.a2a.A2aTaskRepository
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.a2a.A2aCards
import de.alpdesk.services.a2a.A2aTaskCleanupJob
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.mcp.FakeMcpSoftware
import de.alpdesk.services.task.FakeStoreClient
import de.alpdesk.services.task.TaskApi
import de.alpdesk.services.tenant.TenantDatabases
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.a2aproject.sdk.A2A
import org.a2aproject.sdk.client.ClientEvent
import org.a2aproject.sdk.client.MessageEvent
import org.a2aproject.sdk.client.TaskEvent
import org.a2aproject.sdk.client.TaskUpdateEvent
import org.a2aproject.sdk.client.config.ClientConfig
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext
import org.a2aproject.sdk.spec.A2AClientException
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.DataPart
import org.a2aproject.sdk.spec.ListTasksParams
import org.a2aproject.sdk.spec.Message
import org.a2aproject.sdk.spec.Task
import org.a2aproject.sdk.spec.TaskQueryParams
import org.a2aproject.sdk.spec.TaskState
import org.a2aproject.sdk.spec.TextPart
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The tenant's assistant as an A2A agent at /a2a (docs/architecture/a2a.md, roadmap point 52), checked with the official
 * SDK's own client: the neutral public card and the key's extended card, a question answered as a Message, a streamed
 * one kept as the key's task, the doors of a key, the scheduled tasks as A2A tasks, the extensions' descriptions.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
@Property(name = "test.local-webhooks", value = "true")
class A2aServerSpec extends Specification {

    /** TaskNotFoundError's code, as it stands in an answer. */
    static final String TASK_NOT_FOUND = "-32001"

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    EmbeddedServer server

    @Inject
    FakeAiModels fake

    @Inject
    FakeStoreClient store

    @Inject
    A2aTaskCleanupJob cleanup

    @Inject
    A2aTaskRepository taskRows

    @Inject
    A2aTaskIndexRepository taskIndex

    @Inject
    TenantDatabases databases

    @Inject
    TenantRepository tenants

    @Shared
    AiApi api

    @Shared
    TaskApi tasks

    def setup() {
        api = api ?: new AiApi(client)
        tasks = tasks ?: new TaskApi(api)
        TaskApi.mailbox()
        fake.reset()
        store.reset()
    }

    def "the public card is anonymous and neutral, names /a2a and both extensions"() {
        when:
        AgentCard card = A2A.getAgentCard(server.URL.toString())

        then:
        card.name() == "AI assistant"
        card.provider().organization() == "Zirmo"
        card.skills().isEmpty()
        card.supportedInterfaces()[0].protocolBinding() == "JSONRPC"
        card.supportedInterfaces()[0].url() == "${server.URL}/a2a".toString()
        card.capabilities().streaming()
        card.capabilities().extendedAgentCard()
        card.capabilities().extensions()*.uri() == [A2aCards.ASSISTANT_EXTENSION, "urn:alpdesk-ai:a2a:asker:1"]
        card.capabilities().extensions().every { !it.required() }
        card.securitySchemes().containsKey("key")
    }

    def "behind a proxy that ends TLS the card names the https address the proxy forwards"() {
        when:
        def card = client.toBlocking().retrieve(HttpRequest.GET("/.well-known/agent-card.json")
                .header("X-Forwarded-Proto", "https").header("Host", "ai.example.com"), Map)

        then:
        (card.supportedInterfaces as List<Map>)[0].url == "https://ai.example.com/a2a"
        (card.provider as Map).url == "https://ai.example.com"
    }

    def "the extensions are described by the installation itself, anonymously"() {
        expect:
        client.toBlocking().retrieve("/a2a/extensions/assistant/1").contains(A2aCards.ASSISTANT_EXTENSION)
        client.toBlocking().retrieve("/a2a/extensions/asker/1").contains("urn:alpdesk-ai:a2a:asker:1")
    }

    def "the extended card is the key's: the brand, its description, the answer, the templates and its tasks as skills"() {
        given:
        def setup = answering()
        def template = api.call(setup.admin, "POST", "/tenant/admin/assistant/templates",
                [name: "Kurzfassung", instructions: "Antworte kurz"]) as Map
        def key = a2aKey(setup.admin, [description: "Das Wissen der Musterfirma"])

        when:
        AgentCard card = sdkClient(false, new LinkedBlockingQueue()).getExtendedAgentCard(auth(key))

        then:
        card.name() == (api.call(setup.admin, "GET", "/tenant/branding") as Map).name
        card.description() == "Das Wissen der Musterfirma"
        card.skills()*.id() == ["answer", "template:" + template.id]
    }

    def "the extended card says what the assistant offers beyond the documents - here the key's software"() {
        given:
        def setup = answering()
        def software = api.call(setup.admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Buchhaltung", baseUrl: new FakeMcpSoftware().url()]) as Map
        def find = (software.tools as List<Map>).find { it.name == "find_invoice" }
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${software.id}/tools/${find.id}", [enabled: true])
        def key = a2aKey(setup.admin, [description: "Das Wissen der Musterfirma", connectionIds: [software.id]])

        when:
        AgentCard card = sdkClient(false, new LinkedBlockingQueue()).getExtendedAgentCard(auth(key))

        then:
        card.description() == "Das Wissen der Musterfirma\n\nIt can also read the connected software Buchhaltung - " +
                "hand it those parts of a question that concern the company, with their full context."
    }

    def "a plain question is answered as one Message with its sources - nothing stored"() {
        given:
        def setup = answering()
        def key = a2aKey(setup.admin)
        def events = new LinkedBlockingQueue<ClientEvent>()
        def a2a = sdkClient(false, events)

        when:
        a2a.sendMessage(A2A.toUserMessage("Wann ist die Wartung?"), auth(key))
        def event = events.poll(20, TimeUnit.SECONDS)

        then:
        event instanceof MessageEvent
        text((event as MessageEvent).message.parts()).contains("Wann ist die Wartung?")
        (api.call(setup.admin, "GET", "/tenant/chat/conversations") as List).isEmpty()

        when: "the retention passed"
        def namedBefore = taskIndex.findTenantIds().contains(setup.tenant.id as Long)
        cleanup.expire(Instant.now().plus(25, ChronoUnit.HOURS))

        then: "the task id the answer named is forgotten too - no task was ever stored for it"
        namedBefore
        !taskIndex.findTenantIds().contains(setup.tenant.id as Long)

        cleanup:
        a2a?.close()
    }

    def "ids without a task are forgotten a batch at a time"() {
        given: "more ids of plain answers than one run of the cleanup takes"
        def setup = answering()
        Long tenantId = setup.tenant.id as Long
        Instant longAgo = Instant.now().minus(3, ChronoUnit.DAYS)
        taskIndex.saveAll((1..5001).collect { new A2aTaskIndex(UUID.randomUUID().toString(), tenantId, 1L, longAgo) })

        when:
        cleanup.expire(Instant.now())

        then:
        taskIndex.findTaskIdsCreatedBefore(tenantId, Instant.now()).size() == 1

        when:
        cleanup.expire(Instant.now())

        then:
        taskIndex.findTaskIdsCreatedBefore(tenantId, Instant.now()).isEmpty()
    }

    def "a streamed question is a task of the key: appended answer, completed, found by GetTask and ListTasks - not by another key"() {
        given:
        def setup = answering()
        def key = a2aKey(setup.admin)
        def events = new LinkedBlockingQueue<ClientEvent>()
        def a2a = sdkClient(true, events)

        when:
        a2a.sendMessage(A2A.toUserMessage("Was gilt für Urlaub?"), auth(key))
        Task last = awaitFinal(events)

        then:
        last.status().state() == TaskState.TASK_STATE_COMPLETED
        text(last.artifacts()*.parts().flatten()).contains("Was gilt für Urlaub?")

        when:
        Task fetched = a2a.getTask(new TaskQueryParams(last.id()), auth(key))
        def listed = a2a.listTasks(ListTasksParams.builder().pageSize(10).build(), auth(key))

        then:
        fetched.status().state() == TaskState.TASK_STATE_COMPLETED
        listed.tasks()*.id() == [last.id()]
        listed.tasks()[0].artifacts().isEmpty()
        listed.nextPageToken() == ""

        and: "ListTasks filters by the context"
        a2a.listTasks(ListTasksParams.builder().contextId(last.contextId()).build(), auth(key)).tasks()*.id() == [last.id()]
        a2a.listTasks(ListTasksParams.builder().contextId("another").build(), auth(key)).tasks().isEmpty()

        when:
        a2a.getTask(new TaskQueryParams(last.id()), auth(a2aKey(setup.admin)))

        then:
        thrown(A2AClientException)

        when: "SubscribeToTask is asked for the task - by its key and by another one"
        String own = subscribe(key, last.id())
        String foreign = subscribe(a2aKey(setup.admin), last.id())

        then: "for another key the task does not exist"
        !own.contains(TASK_NOT_FOUND)
        foreign.contains(TASK_NOT_FOUND)

        when: "the task's id was recorded long ago, the task itself changed just now"
        def owner = taskIndex.findById(last.id()).get()
        taskIndex.update(new A2aTaskIndex(owner.taskId(), owner.tenantId(), owner.mcpKeyId(),
                Instant.now().minus(3, ChronoUnit.DAYS)))
        cleanup.expire(Instant.now())

        then: "it stays, with its id - only an id without a task is forgotten"
        a2a.getTask(new TaskQueryParams(last.id()), auth(key)).id() == last.id()

        when: "the retention passed - also for a task whose row still says working (lost to a restart, a run nobody mirrors)"
        def tenantDatabase = databases.dataSource(tenants.findById(setup.tenant.id as Long).get().databaseName())
        def row = taskRows.find(tenantDatabase, last.id()).get()
        taskRows.save(tenantDatabase, row.id(), row.mcpKeyId(), "TASK_STATE_WORKING", row.contextId(), null, row.task())
        cleanup.expire(Instant.now().plus(25, ChronoUnit.HOURS))
        a2a.getTask(new TaskQueryParams(last.id()), auth(key))

        then:
        thrown(A2AClientException)
        taskRows.find(tenantDatabase, last.id()).isEmpty()

        cleanup:
        a2a?.close()
    }

    def "a streamed question that fails keeps none of the files it created"() {
        given:
        def setup = answering()
        def key = a2aKey(setup.admin)
        def events = new LinkedBlockingQueue<ClientEvent>()
        def a2a = sdkClient(true, events)
        fake.failAfterTool = true

        when:
        a2a.sendMessage(A2A.toUserMessage("Bitte [[create_file a.txt TXT]]"), auth(key))
        Task last = awaitFinal(events)

        then:
        last.status().state() == TaskState.TASK_STATE_FAILED
        def stored = Path.of("build/test-storage/tenants/${setup.tenant.id}")
        !Files.exists(stored) || Files.walk(stored).noneMatch { Files.isRegularFile(it) }

        cleanup:
        a2a?.close()
    }

    def "a key opens only its doors; other tokens are refused at /a2a"() {
        given:
        def setup = answering()
        def mcpOnly = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Nur MCP"]) as Map).plainKey as String
        def a2aOnly = a2aKey(setup.admin, [mcp: false])
        def apiKey = api.admin("POST", "/admin/tenants/${setup.tenant.id}/api-keys", [name: "Software"]).plainKey as String
        def body = '{"jsonrpc":"2.0","id":1,"method":"GetExtendedAgentCard","params":{}}'

        expect:
        status(mcpOnly, "/a2a", body) == HttpStatus.UNAUTHORIZED
        status(a2aOnly, "/a2a", body) == HttpStatus.OK
        status(a2aOnly, "/mcp", '{"jsonrpc":"2.0","id":1,"method":"tools/list"}') == HttpStatus.UNAUTHORIZED
        status(apiKey, "/a2a", body) == HttpStatus.FORBIDDEN
        status(api.userToken(setup.tenant.id), "/a2a", body) == HttpStatus.FORBIDDEN
        status(null, "/a2a", body) == HttpStatus.UNAUTHORIZED
        api.error(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Keine Tür", mcp: false, a2a: false]) ==
                "The key must open /mcp, /a2a or both"
    }

    def "a door closed by changing the key is closed at the next request; left out it stays as it was"() {
        given:
        def setup = answering()
        def created = api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Beide", mcp: true, a2a: true]) as Map
        def path = "/tenant/admin/mcp-keys/${created.key.id}"
        def key = created.plainKey as String
        def body = '{"jsonrpc":"2.0","id":1,"method":"GetExtendedAgentCard","params":{}}'

        when:
        api.call(setup.admin, "PUT", path, [name: "Beide", a2a: false])

        then:
        status(key, "/a2a", body) == HttpStatus.UNAUTHORIZED

        when:
        api.call(setup.admin, "PUT", path, [name: "Umbenannt"])
        def kept = (api.call(setup.admin, "GET", "/tenant/admin/mcp-keys") as List<Map>).find { it.id == created.key.id }

        then:
        kept.mcp == true
        kept.a2a == false
        status(key, "/a2a", body) == HttpStatus.UNAUTHORIZED

        and: "closing the last door is refused"
        api.error(setup.admin, "PUT", path, [name: "Keine", mcp: false]) == "The key must open /mcp, /a2a or both"
    }

    def "a webhook sent with a plain SendMessage is refused - it would be stored before the task has its key"() {
        given:
        def setup = answering()
        def key = a2aKey(setup.admin)
        def body = io.micronaut.serde.ObjectMapper.getDefault().writeValueAsString([jsonrpc: "2.0", id: 1,
                method: "SendMessage", params: [message: [role: "ROLE_USER", messageId: UUID.randomUUID().toString(),
                parts: [[text: "Frage"]]], configuration: [returnImmediately: true,
                taskPushNotificationConfig: [url: "https://example.com/hook", token: "t"]]]])

        when:
        def error = rpc(key, body).error as Map

        then:
        error.code == -32602
        (error.message as String).contains("CreateTaskPushNotificationConfig")
    }

    def "0.3 method names, broken JSON, a template unknown and a link as a file are refused as A2A says"() {
        given:
        def setup = answering()
        def key = a2aKey(setup.admin)

        expect:
        rpc(key, '{"jsonrpc":"2.0","id":1,"method":"message/send","params":{}}').error.code == -32601
        rpc(key, '{"jsonrpc":"2.0","id":1,').error.subMap(["code", "message"]) ==
                [code: -32700, message: "Request body is not valid JSON"]
        rpc(key, send([[text: "Frage"]], [(A2aCards.ASSISTANT_EXTENSION): [template: "Gibt es nicht"]])).error.code == -32602
        rpc(key, send([[text: "Frage"], [url: "https://example.com/a.txt", filename: "a.txt", mediaType: "text/plain"]]))
                .error.code == -32004
        rpc(key, send([[text: "Frage"], [raw: "AAEC", filename: "bild.png", mediaType: "image/png"]])).error.code == -32005
        rpc(key, send([[data: [a: 1]]])).error.code == -32602
    }

    def "a text file sent with the question is read for the answer"() {
        given:
        def setup = answering()
        def key = a2aKey(setup.admin)
        def raw = Base64.encoder.encodeToString("Inhalt der Notiz".getBytes("UTF-8"))

        when:
        def result = rpc(key, send([[text: "Lies die Notiz"], [raw: raw, filename: "notiz.txt", mediaType: "text/plain"]])).result

        then:
        (result.message.parts as List<Map>)[0].text.contains("Lies die Notiz")
        fake.chats.last().messages.toString().contains("Inhalt der Notiz")
    }

    def "a scheduled task assigned to the key starts over A2A: a task following the run, its report and findings at its end"() {
        given:
        def setup = tasks.tenant()
        store.write(TaskApi.LOGS + "/" + LocalDate.now(ZoneId.of("Europe/Berlin")) + "_app.log",
                "INFO start\nERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, "a2a-${UUID.randomUUID()}@example.com"))
        def key = a2aKey(setup.token, [taskIds: [task.id]])
        def a2a = sdkClient(false, new LinkedBlockingQueue())

        when:
        def answer = rpc(key, send([[text: "los"]], [(A2aCards.ASSISTANT_EXTENSION): [task: task.name]])).result.task as Map

        then:
        // A blocking SendMessage waits for the run's end; returnImmediately would answer at once.
        answer.status.state in ["TASK_STATE_SUBMITTED", "TASK_STATE_WORKING", "TASK_STATE_COMPLETED"]

        when:
        Task ended = awaitTask(a2a, answer.id as String, key)

        then:
        ended.status().state() == TaskState.TASK_STATE_COMPLETED
        ended.artifacts()*.name() == ["report", "findings"]
        text(ended.artifacts()[0].parts()).contains("Run ")
        (ended.artifacts()[1].parts()[0] as DataPart).data().containsKey("findings")

        and: "the run shows who started it"
        (api.call(setup.token, "GET", "/tenant/admin/tasks/${task.id}/runs") as List<Map>)[0].startedBy == "A2A"

        and: "a task not assigned to the key is refused"
        rpc(key, send([[text: "los"]], [(A2aCards.ASSISTANT_EXTENSION): [task: "Fremd"]])).error.code == -32602

        cleanup:
        a2a?.close()
    }

    def "a webhook gets the events of a run started over A2A with its token; plain HTTP elsewhere, internal hosts and another key's task are refused; the webhooks go with the task"() {
        given:
        def setup = tasks.tenant()
        store.write(TaskApi.LOGS + "/" + LocalDate.now(ZoneId.of("Europe/Berlin")) + "_app.log", "ERROR Timeout\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, "a2a-${UUID.randomUUID()}@example.com"))
        def key = a2aKey(setup.token, [taskIds: [task.id]])
        def received = new java.util.concurrent.CopyOnWriteArrayList<Map>()
        def webhook = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        webhook.createContext("/hook") { exchange ->
            received << [token: exchange.requestHeaders.getFirst("X-A2A-Notification-Token"),
                         body : new String(exchange.requestBody.readAllBytes(), "UTF-8")]
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        webhook.start()
        def hook = "http://127.0.0.1:${webhook.address.port}/hook".toString()

        when: "started at once, the webhook registered for it"
        def started = rpc(key, send([[text: "los"]], [(A2aCards.ASSISTANT_EXTENSION): [task: task.name]], true)).result.task as Map
        def created = rpc(key, json([jsonrpc: "2.0", id: 2, method: "CreateTaskPushNotificationConfig",
                                     params : [taskId: started.id, url: hook, token: "geheim-123"]]))

        then:
        created.result.url == hook

        when:
        long until = System.currentTimeMillis() + 60_000
        while (!received.any { it.body.contains("TASK_STATE_COMPLETED") } && System.currentTimeMillis() < until) {
            Thread.sleep(200)
        }

        then:
        received.any { it.body.contains("TASK_STATE_COMPLETED") }
        received.every { it.token == "geheim-123" }

        and: "refused: another address than public https, another key's task"
        rpc(key, json([jsonrpc: "2.0", id: 3, method: "CreateTaskPushNotificationConfig",
                       params : [taskId: started.id, url: "http://example.com/hook"]])).error.code == -32602
        rpc(a2aKey(setup.token), json([jsonrpc: "2.0", id: 4, method: "CreateTaskPushNotificationConfig",
                                       params : [taskId: started.id, url: hook]])).error.code == -32001

        when: "the task is deleted after its retention - its webhooks with it"
        cleanup.expire(Instant.now().plus(25, ChronoUnit.HOURS))

        then:
        rpc(key, json([jsonrpc: "2.0", id: 5, method: "ListTaskPushNotificationConfigs", params: [taskId: started.id]]))
                .error.code == -32001

        cleanup:
        webhook?.stop(0)
    }

    def "a webhook's address must be public https"() {
        when:
        new de.alpdesk.services.a2a.PushAddresses().validate(url)

        then:
        thrown(IllegalArgumentException)

        where:
        url << ["http://example.com/hook", "https://127.0.0.1/hook", "https://10.0.0.5/hook", "ftp://example.com"]
    }

    private static String json(Map body) {
        io.micronaut.serde.ObjectMapper.getDefault().writeValueAsString(body)
    }

    private Map answering() {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = api.chatModel()
        api.allow(tenant.id, chat)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        api.call(admin, "PUT", "/tenant/admin/assistant", [instructions: "", answerSource: "DOCUMENTS_ONLY",
                creativity: "MEDIUM", reasoning: "LOW", searchResults: 5, defaultModelId: chat.id])
        [tenant: tenant, admin: admin, chat: chat]
    }

    private String a2aKey(String admin, Map more = [:]) {
        (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "A2A " + UUID.randomUUID(), a2a: true] + more) as Map)
                .plainKey as String
    }

    private org.a2aproject.sdk.client.Client sdkClient(boolean streaming, LinkedBlockingQueue<ClientEvent> events) {
        def card = A2A.getAgentCard(server.URL.toString())
        org.a2aproject.sdk.client.Client.builder(card)
                .withTransport(JSONRPCTransport, new JSONRPCTransportConfigBuilder())
                .clientConfig(ClientConfig.builder().setStreaming(streaming).build())
                .addConsumer({ ClientEvent event, AgentCard c -> events.add(event) })
                .streamingErrorHandler({ Throwable t -> if (t != null) t.printStackTrace() })
                .build()
    }

    private static ClientCallContext auth(String key) {
        new ClientCallContext([:], [Authorization: "Bearer " + key])
    }

    private static String send(List<Map> parts, Map metadata = null, boolean returnImmediately = false) {
        Map message = [role: "ROLE_USER", messageId: UUID.randomUUID().toString(), parts: parts]
        if (metadata != null) {
            message.metadata = metadata
        }
        Map params = [message: message]
        if (returnImmediately) {
            params.configuration = [returnImmediately: true]
        }
        io.micronaut.serde.ObjectMapper.getDefault().writeValueAsString(
                [jsonrpc: "2.0", id: 1, method: "SendMessage", params: params])
    }

    private Map rpc(String key, String body) {
        client.toBlocking().retrieve(HttpRequest.POST("/a2a", body).contentType("application/json")
                .header("A2A-Version", "1.0").bearerAuth(key), Map)
    }

    /** SubscribeToTask for the task: what comes back, events or an error. */
    private String subscribe(String key, String taskId) {
        client.toBlocking().retrieve(HttpRequest.POST("/a2a", json([jsonrpc: "2.0", id: 1, method: "SubscribeToTask",
                params: [id: taskId]])).contentType("application/json").accept("application/json, text/event-stream")
                .header("A2A-Version", "1.0").bearerAuth(key), String)
    }

    private HttpStatus status(String token, String path, String body) {
        try {
            def request = HttpRequest.POST(path, body).contentType("application/json")
                    .accept("application/json, text/event-stream")
            if (token) {
                request = request.bearerAuth(token)
            }
            client.toBlocking().exchange(request, String).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    private static Task awaitTask(org.a2aproject.sdk.client.Client a2a, String id, String key) {
        long until = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < until) {
            Task task = a2a.getTask(new TaskQueryParams(id), auth(key))
            if (task.status().state().isFinal()) {
                return task
            }
            Thread.sleep(500)
        }
        throw new AssertionError("the task did not end within 60 s")
    }

    private static Task awaitFinal(LinkedBlockingQueue<ClientEvent> events) {
        long until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until) {
            def event = events.poll(1, TimeUnit.SECONDS)
            if (event instanceof TaskEvent || event instanceof TaskUpdateEvent) {
                Task task = event instanceof TaskEvent ? (event as TaskEvent).task : (event as TaskUpdateEvent).task
                if (task.status().state().isFinal()) {
                    return task
                }
            }
        }
        throw new AssertionError("no final task event within 30 s")
    }

    private static String text(List parts) {
        parts.findAll { it instanceof TextPart }.collect { (it as TextPart).text() }.join("")
    }
}
