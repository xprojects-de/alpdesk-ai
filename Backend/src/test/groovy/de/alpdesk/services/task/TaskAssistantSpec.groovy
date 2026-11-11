package de.alpdesk.services.task

import de.alpdesk.services.a2a.FakeA2aAgent
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.time.LocalDate
import java.time.ZoneId

import static de.alpdesk.services.task.TaskApi.LOGS

/**
 * A prompt step answered by the tenant's assistant (docs/architecture/tasks.md "The step prompt", assistant: true -
 * roadmap point 54 package E): the knowledge and the software it names, an A2A agent asked through its tool, the check
 * of what it names, and a tenant answering through its agent answered by the agent.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
class TaskAssistantSpec extends Specification {

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
        store.write(LOGS + "/" + LocalDate.now(ZoneId.of("Europe/Berlin")) + "_app.log", "ERROR Timeout to interface X\n")
    }

    def cleanup() {
        agent.stop()
    }

    def "the assistant's prompt gets the knowledge tools and the named software's tool - the agent asked through it"() {
        given:
        def setup = withAgent()
        def task = tasks.create(setup.token, prompting(setup.model,
                'Was sagt der Agent? [[tools]] [[call Fakeagent__ask {"question":"Wie ist der Stand?"}]]',
                "    software: [Fakeagent]\n"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def answer = (run.answers as List<Map>)[0].text as String
        answer.contains("[Fakeagent] Antwort von Fakeagent: Wie ist der Stand?")
        // similarity_search is offered once the tenant has an embedding model - this tenant has none.
        answer.contains("Fakeagent__ask")
        !answer.contains("web_search")
        !answer.contains("run_python")
    }

    def "web: true offers the web search to the assistant's prompt - the task's text is what allows it"() {
        given:
        def setup = tasks.tenant()
        def webSearch = api.admin("POST", "/admin/models", [provider: "TAVILY", kind: "WEB_SEARCH",
                modelName: "basic-" + AiApi.unique(), displayName: "Websuche " + AiApi.unique(), inputPrice: 8, outputPrice: 0]) as Map
        api.allow(setup.tenant.id, webSearch)
        api.call(setup.token, "PUT", "/tenant/admin/provider-keys/TAVILY", [apiKey: "own-key-of-TAVILY"])
        def task = tasks.create(setup.token, prompting(setup.model, "Was gibt es Neues? [[tools]]", "    web: true\n"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        ((run.answers as List<Map>)[0].text as String).contains("web_search")
    }

    def "without assistant: true the step keeps to its files"() {
        given:
        def setup = withAgent()
        def task = tasks.create(setup.token, prompting(setup.model, "Zusammenfassen [[tools]]", "", false))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def answer = (run.answers as List<Map>)[0].text as String
        !answer.contains("similarity_search")
        !answer.contains("Fakeagent__ask")
    }

    def "the check: unknown area, software and template; the fields only with assistant: true; an agent without A2A"() {
        given:
        def setup = withAgent()

        expect:
        codes(setup, prompting(setup.model, "x", "    areas: [Gibtsnicht]\n")) == ["task.areaUnknown"]
        codes(setup, prompting(setup.model, "x", "    software: [Gibtsnicht]\n")) == ["task.softwareUnknown"]
        codes(setup, prompting(setup.model, "x", "    template: Gibtsnicht\n")) == ["task.templateUnknown"]
        codes(setup, prompting(setup.model, "x", "    web: true\n", false)) == ["task.fieldNotTaken"]

        when:
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, a2aAllowed: false, tasksAllowed: true])

        then:
        codes(setup, prompting(setup.model, "x", "    software: [Fakeagent]\n")) == ["task.a2aNotAllowed"]
    }

    def "software or a template deleted since the task was checked fails the run - it does not answer without them"() {
        given:
        def setup = withAgent()
        def template = api.call(setup.token, "POST", "/tenant/admin/assistant/templates",
                [name: "Kurz", instructions: "Antworte kurz."]) as Map
        def withSoftware = tasks.create(setup.token, prompting(setup.model, "x", "    software: [Fakeagent]\n"))
        def withTemplate = tasks.create(setup.token, prompting(setup.model, "x", "    template: Kurz\n"))
        api.status(setup.token, "DELETE", "/tenant/admin/mcp-connections/${setup.connection.id}")
        api.status(setup.token, "DELETE", "/tenant/admin/assistant/templates/${template.id}")

        when:
        def first = tasks.awaitRun(setup.token, withSoftware.id, tasks.start(setup.token, withSoftware.id))
        def second = tasks.awaitRun(setup.token, withTemplate.id, tasks.start(setup.token, withTemplate.id))

        then:
        [first, second].every { (it.run as Map).state == "FAILED" && (it.run as Map).errorCode == "task.assistantGone" }
    }

    def "a tenant answering through its agent: the assistant's prompt is answered by the agent"() {
        given:
        def setup = withAgent(true)
        def task = tasks.create(setup.token, prompting(setup.model, "Frage an den Workflow", ""))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        ((run.answers as List<Map>)[0].text as String).startsWith("Antwort von Fakeagent:")
        def sent = agent.messages().last()
        def asker = sent.metadata["urn:alpdesk-ai:a2a:asker:1"] as Map
        asker.task == task.name
        !asker.containsKey("user")

        and: "the agent gets the step's prompt and its inputs - not the rules our model gets"
        (sent.text as String).startsWith("Frage an den Workflow")
        (sent.text as String).contains("ERROR Timeout to interface X")
        !(sent.text as String).contains("scheduled task")
    }

    def "a tenant answering through its agent whose A2A the admin took away: the run fails, the agent hears nothing"() {
        given:
        def setup = withAgent(true)
        def task = tasks.create(setup.token, prompting(setup.model, "Frage an den Workflow", ""))
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, a2aAllowed: false, tasksAllowed: true])

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "FAILED"
        (run.run as Map).errorCode == "a2a.notAllowed"
        agent.messages().isEmpty()
    }

    def "a tenant answering through its agent: choices are asked for as the answer's last line"() {
        given:
        def setup = withAgent(true)
        def task = tasks.create(setup.token, prompting(setup.model, "Passt das?", "",
                true, "    choices: [ok, nachbessern]\n"))

        when:
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (agent.messages().last().text as String).contains("CHOICE: <word>")
        (agent.messages().last().text as String).contains("ok, nachbessern")
    }

    def "a tenant answering through its agent needs no model for the assistant's prompt - another tenant does"() {
        given:
        def answering = withAgent(true)
        def other = withAgent()
        def withoutModel = prompting(null, "Frage an den Workflow", "")

        expect:
        codes(answering, withoutModel) == []
        codes(other, withoutModel) == ["task.modelRequiredWithoutAgent"]

        when:
        def task = tasks.create(answering.token, withoutModel)
        def run = tasks.awaitRun(answering.token, task.id, tasks.start(answering.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        ((run.answers as List<Map>)[0].text as String).startsWith("Antwort von Fakeagent: Frage an den Workflow")
    }

    private Map withAgent(boolean answersChat = false) {
        def setup = tasks.tenant()
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, a2aAllowed: true, tasksAllowed: true])
        def connection = api.call(setup.token, "POST", "/tenant/admin/mcp-connections", [name: "Fakeagent", baseUrl: agent.url(),
                                                                                        protocol: "A2A", answersChat: answersChat]) as Map
        assert connection.toolsError == null
        def user = api.user(setup.tenant.id)
        api.grant(setup.token, connection.id, user.id, "READ")
        setup + [connection: connection]
    }

    private List codes(Map setup, String source) {
        (tasks.validate(setup.token, source).errors as List<Map>)*.code
    }

    /** model null: the step names none. */
    private static String prompting(Map model, String prompt, String more, boolean assistant = true, String choices = "") {
        "version: 1\n" +
                "name: Frage ${UUID.randomUUID()}\n" +
                "notify: [it@example.com]\n" +
                "steps:\n" +
                "  - id: logs\n" +
                "    type: read_files\n" +
                "    store: Logdateien\n" +
                "    pattern: \"{yyyy-MM-dd}_*.log\"\n" +
                "  - id: ask\n" +
                "    type: prompt\n" +
                (model == null ? "" : "    model: ${model.displayName}\n") +
                "    input: [logs]\n" +
                "    prompt: '${prompt}'\n" +
                (assistant ? "    assistant: true\n" : "") + choices + more
    }
}
