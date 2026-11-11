package de.alpdesk.services.task

import de.alpdesk.services.a2a.FakeA2aAgent
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.a2aproject.sdk.spec.DataPart
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant

import static de.alpdesk.services.task.TaskApi.LOGS

/**
 * The step call_agent of the scheduled tasks (docs/architecture/tasks.md "call_agent", roadmap point 54 package D): the
 * clear rows of an extract step sent to an A2A agent - a message per row or one with all, the prompt as text, the row
 * as data -, a Task waited for, the agent asking back failing the row, and the check of the agent, its skill and the
 * admin's switch.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
@Property(name = "app.ai.timeout", value = "1s")
@Property(name = "app.a2a.timeout", value = "20s")
class TaskAgentSpec extends Specification {

    static final String INBOX = LOGS + "/inbox"

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels models

    @Inject
    FakeStoreClient store

    @Shared
    AiApi api

    @Shared
    TaskApi tasks

    FakeA2aAgent agent = new FakeA2aAgent()

    def setup() {
        api = api ?: new AiApi(client)
        tasks = tasks ?: new TaskApi(api)
        TaskApi.mailbox()
        models.reset()
        store.reset()
    }

    def cleanup() {
        agent.stop()
    }

    def "every clear row goes to the agent as data with its source, the prompt as text and the skill in the metadata"() {
        given:
        def setup = withAgent()
        documents()
        def task = tasks.create(setup.token, sending(setup.model, "row", "Buche die Rechnung"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def result = (run.steps as List<Map>).find { it.stepId == "send" }.result as Map
        result.sent == 2
        result.failed == 0
        def messages = agent.messages()
        messages.size() == 2
        messages*.text == ["Buche die Rechnung", "Buche die Rechnung"]
        def data = (messages[0].parts as List).find { it instanceof DataPart }.data() as Map
        data.date == "2026-03-01"
        data.journal == "received"
        (data.source as String).startsWith("a.txt#")
        !messages.any { ((it.parts as List).find { p -> p instanceof DataPart }.data() as Map).source.toString().startsWith("c.txt#") }
        def asker = messages[0].metadata["urn:alpdesk-ai:a2a:asker:1"] as Map
        asker.task == task.name
        asker.skills == ["rechnungen"]
        !asker.containsKey("user")
    }

    def "each: all sends every clear row in one message as its list rows; a Task is waited for"() {
        given:
        def setup = withAgent()
        documents()
        def task = tasks.create(setup.token, sending(setup.model, "all", "Alle [[task]]"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        agent.messages().size() == 1
        (((agent.messages()[0].parts as List).find { it instanceof DataPart }.data() as Map).rows as List).size() == 2
        ((run.steps as List<Map>).find { it.stepId == "send" }.result as Map).calls[0].answer.contains("Antwort von Fakeagent")
    }

    def "a blocking agent slower than app.ai.timeout is waited for up to app.a2a.timeout"() {
        given:
        def setup = withAgent()
        documents()
        agent.slowMillis = 1_500
        def task = tasks.create(setup.token, sending(setup.model, "row", "Buche [[slow]]"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def result = (run.steps as List<Map>).find { it.stepId == "send" }.result as Map
        result.sent == 2
        result.failed == 0
    }

    def "the agent asking back fails the rows - a task has no person to answer; the run ends unclear"() {
        given:
        def setup = withAgent()
        documents()
        def task = tasks.create(setup.token, sending(setup.model, "row", "Buche [[ask-back Welches Konto?]]"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "UNCLEAR"
        def result = (run.steps as List<Map>).find { it.stepId == "send" }.result as Map
        result.failed == 2
        (result.calls as List<Map>).every { (it.answer as String).startsWith("task.agentAsksBack") }
    }

    def "a task saved before the admin took A2A away sends nothing to its agent - the run fails"() {
        given:
        def setup = withAgent()
        documents()
        def task = tasks.create(setup.token, sending(setup.model, "row", "Buche die Rechnung"))
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, a2aAllowed: false, tasksAllowed: true])

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "FAILED"
        agent.messages().isEmpty()
    }

    def "the check: an unknown agent, an MCP connection named, an unknown skill, a tenant without A2A"() {
        given:
        def setup = withAgent()
        api.call(setup.token, "POST", "/tenant/admin/mcp-connections", [name: "Software", baseUrl: agent.url()])

        expect:
        codes(setup, sending(setup.model, "row", "x").replace("agent: Fakeagent", "agent: Niemand")) == ["task.agentUnknown"]
        codes(setup, sending(setup.model, "row", "x").replace("agent: Fakeagent", "agent: Software")) == ["task.agentUnknown"]
        codes(setup, sending(setup.model, "row", "x").replace("skill: rechnungen", "skill: lager")) == ["task.skillUnknown"]
        codes(setup, sending(setup.model, "lots", "x")) == ["task.eachUnknown"]

        when:
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, a2aAllowed: false, tasksAllowed: true])

        then:
        codes(setup, sending(setup.model, "row", "x")) == ["task.a2aNotAllowed"]
    }

    // Here for this spec's settings (app.ai.timeout below app.a2a.timeout) - a context of its own would cost seconds.
    def "a streamed question at /a2a waits for the tenant's agent as long as the chat does - not twice our model's time"() {
        given: "an agent answering the whole chat in one piece after 2.5 s - more than twice app.ai.timeout"
        def tenant = api.tenant()
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, a2aAllowed: true])
        def admin = api.tenantAdminToken(tenant.id)
        agent.key = "agent-key-123456"
        agent.slowMillis = 2_500
        agent.streams = false
        api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Fakeagent", baseUrl: agent.url(),
                protocol: "A2A", apiKey: agent.key, answersChat: true])
        def key = (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Plattform", a2a: true]) as Map).plainKey as String

        when:
        def body = [jsonrpc: "2.0", id: 1, method: "SendStreamingMessage", params: [message: [role: "ROLE_USER",
                messageId: UUID.randomUUID().toString(), parts: [[text: "Langsam [[slow]]"]]]]]
        String stream = client.toBlocking().retrieve(HttpRequest.POST("/a2a", body).header("A2A-Version", "1.0")
                .accept(MediaType.TEXT_EVENT_STREAM_TYPE).bearerAuth(key), String)
        def events = stream.readLines().findAll { it.startsWith("data:") }
                .collect { io.micronaut.serde.ObjectMapper.getDefault().readValue(it.substring(5).trim(), Map) }

        then:
        events.last().result.statusUpdate.status.state == "TASK_STATE_COMPLETED"
    }

    private Map withAgent() {
        def setup = tasks.tenant()
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, a2aAllowed: true, tasksAllowed: true])
        def connection = api.call(setup.token, "POST", "/tenant/admin/mcp-connections", [name: "Fakeagent", baseUrl: agent.url(),
                                                                                        protocol: "A2A"]) as Map
        assert connection.toolsError == null
        setup + [connection: connection]
    }

    private void documents() {
        store.folders.add(INBOX)
        store.put("${INBOX}/a.txt", "date: 2026-03-01\ngross: 10".bytes, Instant.parse("2026-03-01T00:00:00Z"))
        store.put("${INBOX}/b.txt", "date: 2026-03-02\ngross: 2".bytes, Instant.parse("2026-03-02T00:00:00Z"))
        store.put("${INBOX}/c.txt", "date: 2026-03-03\n[[unclear zwei Steuersätze]]".bytes, Instant.parse("2026-03-03T00:00:00Z"))
    }

    private List codes(Map setup, String source) {
        (tasks.validate(setup.token, source).errors as List<Map>)*.code
    }

    private static String sending(Map model, String each, String prompt) {
        """\
        version: 1
        name: Buchen ${UUID.randomUUID()}
        notify: [it@example.com]
        steps:
          - id: documents
            type: read_documents
            store: Logdateien
            folder: inbox
            pattern: '*.txt'
          - id: rows
            type: extract
            model: ${model.displayName}
            input: documents
            fields:
              - {name: date, type: date, required: true}
              - {name: gross, type: amount}
          - id: send
            type: call_agent
            input: rows
            agent: Fakeagent
            skill: rechnungen
            each: ${each}
            prompt: '${prompt}'
            with:
              journal: received
        """.stripIndent()
    }
}
