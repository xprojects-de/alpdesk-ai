package de.alpdesk.services.task

import de.alpdesk.repositories.task.TaskRepository
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.tenant.TenantDatabases
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch

import static de.alpdesk.services.task.TaskApi.LOGS
import static de.alpdesk.services.task.TaskApi.TASKS

/**
 * Starting a task without a person (docs/architecture/tasks.md): its schedule (TaskScheduler, driven by the spec's
 * time), the client software with a key the task is assigned to (/api/v1/tasks), the tenant's MCP server for a task
 * opened to it - and cancelling a run. Same context as TaskRunSpec: FakeStoreClient, FakeAiModels, GreenMail.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
@Property(name = "app.task.mcp-wait", value = "3s")
class TaskStartSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels models

    @Inject
    FakeStoreClient store

    @Inject
    TaskScheduler scheduler

    @Inject
    TaskRepository taskRepository

    @Inject
    TenantRepository tenants

    @Inject
    TenantDatabases databases

    @Inject
    de.alpdesk.repositories.task.TaskRunRepository runs

    @Shared
    AiApi api

    @Shared
    TaskApi tasks

    def setup() {
        api = api ?: new AiApi(client)
        tasks = tasks ?: new TaskApi(api)
        TaskApi.mailbox()
        models.reset()
        store.reset()
    }

    def "a due schedule starts the task once and moves on in its rhythm - a task still running is skipped"() {
        given:
        def setup = tasks.tenant()
        store.write(log(), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, scheduled(setup.model, "every: 5h"))
        Instant due = dueNow(task.id as Long)

        when:
        scheduler.startDue(due.plusSeconds(10))
        def run = (api.call(setup.token, "GET", "${TASKS}/${task.id}/runs") as List<Map>)[0]

        then:
        run.startedBy == "SCHEDULE"
        tasks.awaitRun(setup.token, task.id, run.id).run.state == "DONE"
        taskRepository.findById(task.id as Long).get().nextRunAt() == due.plus(5, ChronoUnit.HOURS)

        when: "due again while a run is still open - held while it reads a new line (without one it would not read, nor wait)"
        store.write(log(), "ERROR Timeout to interface X\nERROR Timeout to interface Y\n")
        store.hold = new CountDownLatch(1)
        tasks.start(setup.token, task.id)
        Instant again = dueNow(task.id as Long)
        scheduler.startDue(again.plusSeconds(10))
        store.hold.countDown()

        then: "skipped - but its next time moved on all the same"
        (api.call(setup.token, "GET", "${TASKS}/${task.id}/runs") as List<Map>)*.startedBy == ["TENANT_ADMIN", "SCHEDULE"]
        taskRepository.findById(task.id as Long).get().nextRunAt() == again.plus(5, ChronoUnit.HOURS)
    }

    def "after a downtime a schedule runs once and goes on from now"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, scheduled(setup.model, "every: 1h"))
        Instant longAgo = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        setNextRun(task.id as Long, longAgo)
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS)

        when:
        scheduler.startDue(now)
        scheduler.startDue(now.plusSeconds(60))

        then:
        (api.call(setup.token, "GET", "${TASKS}/${task.id}/runs") as List<Map>).size() == 1
        taskRepository.findById(task.id as Long).get().nextRunAt() == now.plus(1, ChronoUnit.HOURS)
    }

    def "a tenant without the switch, or a task switched off, starts nothing on its schedule"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, scheduled(setup.model, "every: 1h"))
        Instant due = dueNow(task.id as Long)
        tasks.allowTasks(setup.tenant, false)

        when:
        scheduler.startDue(due.plusSeconds(10))

        then: "nothing started, the next time moved on - no catching up once it is allowed again"
        api.admin("GET", "/admin/tenants/${setup.tenant.id}/tasks/${task.id}/runs") == []
        taskRepository.findById(task.id as Long).get().nextRunAt() == due.plus(1, ChronoUnit.HOURS)

        when:
        tasks.allowTasks(setup.tenant, true)
        def off = api.call(setup.token, "PUT", "${TASKS}/${task.id}", [source: scheduled(setup.model, "every: 1h"), active: false]) as Map

        then:
        off.nextRunAt == null
        taskRepository.findDue(Instant.now().plus(10, ChronoUnit.DAYS)).every { it.id() != task.id }
    }

    def "the client software starts only the tasks assigned to its key, and reads what a run found"() {
        given:
        def setup = tasks.tenant()
        String mail = "it-" + UUID.randomUUID() + "@example.com"
        store.write(log(), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, mail))
        def other = tasks.create(setup.token, TaskApi.logCheck(setup.model, mail))
        def key = api.call(setup.token, "POST", "/tenant/admin/api-keys", [name: "Software", taskIds: [task.id]]) as Map
        String apiKey = key.plainKey

        expect: "the key lists its task"
        (key.key as Map).taskIds == [task.id]
        (api.call(apiKey, "GET", "/api/v1/tasks") as List<Map>)*.name == [task.name]
        api.status(apiKey, "POST", "/api/v1/tasks/${other.id}/runs").code == 404

        when:
        def started = api.call(apiKey, "POST", "/api/v1/tasks/${task.id}/runs") as Map
        Map run = awaitApiRun(apiKey, task.id, started.runId)

        then: "the findings with their lines - not the lines read"
        run.state == "DONE"
        run.findings*.checkPoint == ["Interface"]
        run.findings[0].severity == "ERROR"
        run.findings[0].evidence*.text == ["ERROR Timeout to interface X"]
        !(run as Map).containsKey("steps")

        and: "the tenant admin sees who started it"
        (tasks.awaitRun(setup.token, task.id, started.runId).run as Map).startedBy == "API"
        (tasks.awaitRun(setup.token, task.id, started.runId).run as Map).startedByKey == (key.key as Map).id

        and: "another key of the same tenant, which the task is not assigned to, reads nothing of the run"
        String otherKey = (api.call(setup.token, "POST", "/tenant/admin/api-keys", [name: "Andere Software"]) as Map).plainKey
        api.status(otherKey, "GET", "/api/v1/tasks/${task.id}/runs/${started.runId}").code == 404

        and: "another tenant's key reaches nothing of it"
        def stranger = tasks.tenant()
        String strangerKey = (api.call(stranger.token, "POST", "/tenant/admin/api-keys", [name: "Fremd"]) as Map).plainKey
        api.status(strangerKey, "GET", "/api/v1/tasks/${task.id}/runs/${started.runId}").code == 404
        api.call(strangerKey, "GET", "/api/v1/tasks") == []
    }

    def "while a run is open the client software gets no findings - the agent's are not verified yet"() {
        given:
        def setup = tasks.tenant()
        store.write(log(), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, "it-" + UUID.randomUUID() + "@example.com"))
        String apiKey = (api.call(setup.token, "POST", "/tenant/admin/api-keys", [name: "Software", taskIds: [task.id]]) as Map).plainKey
        models.verifyHold = new CountDownLatch(1)

        when: "the agent found the timeout, the verification still runs"
        def started = api.call(apiKey, "POST", "/api/v1/tasks/${task.id}/runs") as Map
        awaitStep(setup.token, task.id, started.runId, "check")
        Map open = api.call(apiKey, "GET", "/api/v1/tasks/${task.id}/runs/${started.runId}") as Map

        then:
        open.state == "RUNNING"
        open.findings == []
        open.answers == []

        when:
        models.verifyHold.countDown()
        Map ended = awaitApiRun(apiKey, task.id, started.runId)

        then:
        ended.state == "DONE"
        ended.findings*.checkPoint == ["Interface"]

        cleanup: "a failed assertion must not leave the verification waiting - it would hold the next run's thread"
        models.verifyHold?.countDown()
    }

    def "a run whose verification failed hands on no findings - the agent's were never verified"() {
        given:
        def setup = tasks.tenant()
        store.write(log(), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, "it-" + UUID.randomUUID() + "@example.com"))
        String apiKey = (api.call(setup.token, "POST", "/tenant/admin/api-keys", [name: "Software", taskIds: [task.id]]) as Map).plainKey
        models.verifyHold = new CountDownLatch(1)

        when: "the agent found the timeout, then the verification answers twice without a valid result"
        def started = api.call(apiKey, "POST", "/api/v1/tasks/${task.id}/runs") as Map
        awaitStep(setup.token, task.id, started.runId, "check")
        models.brokenTaskAnswers = 2
        models.verifyHold.countDown()
        Map run = awaitApiRun(apiKey, task.id, started.runId)

        then:
        run.state == "FAILED"
        run.errorCode == "task.resultInvalid"
        run.findings == []

        cleanup:
        models.verifyHold?.countDown()
    }

    def "the client software and MCP get the answers a task hands on - the last answer of each prompt it mails"() {
        given:
        def setup = tasks.tenant()
        store.write(log(), "ERROR Timeout to interface X\n")
        String source = """\
            version: 1
            name: Kette ${UUID.randomUUID()}
            notify: [it@example.com]
            steps:
              - id: logs
                type: read_files
                store: Logdateien
                pattern: "{yyyy-MM-dd}_*.log"
              - id: summary
                type: prompt
                model: ${setup.model.displayName}
                input: [logs]
                prompt: Fasse zusammen.
              - id: mail
                type: send_mail
                input: [summary]
            """.stripIndent()
        def task = tasks.create(setup.token, source)
        String apiKey = (api.call(setup.token, "POST", "/tenant/admin/api-keys", [name: "Software", taskIds: [task.id]]) as Map).plainKey
        String mcpKey = (api.call(setup.token, "POST", "/tenant/admin/mcp-keys", [name: "Claude", taskIds: [task.id]]) as Map).plainKey

        when:
        def started = api.call(apiKey, "POST", "/api/v1/tasks/${task.id}/runs") as Map
        Map run = awaitApiRun(apiKey, task.id, started.runId)
        String viaMcp = tool(mcpKey, "run_task", [task: task.name]).content[0].text

        then:
        (run.answers as List<Map>)*.step == ["summary"]
        ((run.answers as List<Map>)[0].text as String).startsWith("Antwort von summary (Runde 1)")
        viaMcp.contains("summary:\nAntwort von summary (Runde 1)")
    }

    def "a key gets no task of another tenant; changing a key's tasks replaces them"() {
        given:
        def setup = tasks.tenant()
        def stranger = tasks.tenant()
        def foreign = tasks.create(stranger.token, TaskApi.logCheck(stranger.model, "it@example.com"))
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, "it@example.com"))
        def key = (api.call(setup.token, "POST", "/tenant/admin/api-keys", [name: "Software", taskIds: [task.id]]) as Map).key as Map

        expect:
        tasks.refusal(setup.token, "POST", "/tenant/admin/api-keys", [name: "X", taskIds: [foreign.id]]) == [400, "apiKey.taskUnknown"]
        (api.call(setup.token, "PUT", "/tenant/admin/api-keys/${key.id}", [taskIds: []]) as Map).taskIds == []
        (api.call(setup.token, "PUT", "/tenant/admin/api-keys/${key.id}", [name: "Neu"]) as Map).taskIds == []
    }

    def "an MCP key lists and starts only the tasks assigned to it - a quick run answers with its findings"() {
        given:
        def setup = tasks.tenant()
        store.write(log(), "ERROR Timeout to interface X\n")
        def opened = tasks.create(setup.token, TaskApi.logCheck(setup.model, "it@example.com", [name: "Offene Prüfung"]))
        tasks.create(setup.token, TaskApi.logCheck(setup.model, "it@example.com", [name: "Geschlossen"]))
        String key = (api.call(setup.token, "POST", "/tenant/admin/mcp-keys", [name: "Claude", taskIds: [opened.id]]) as Map).plainKey

        when:
        String list = tool(key, "list_tasks", [:]).content[0].text
        Map started = tool(key, "run_task", [task: "offene prüfung"])
        Map closed = tool(key, "run_task", [task: "Geschlossen"])

        then:
        list.contains("Offene Prüfung")
        !list.contains("Geschlossen")
        !started.isError
        (started.content[0].text as String).contains("ended DONE")
        (started.content[0].text as String).contains("Interface (error, finding)")
        closed.isError
        (closed.content[0].text as String).contains("Unknown task")

        and: "the run says it came from MCP"
        (api.call(setup.token, "GET", "${TASKS}/${opened.id}/runs") as List<Map>)[0].startedBy == "MCP"
    }

    def "each MCP key has its own tasks - another key of the tenant and another tenant's key get none; the task names its keys"() {
        given:
        def setup = tasks.tenant()
        def stranger = tasks.tenant()
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, "it@example.com", [name: "Rechnungsprüfung"]))
        def foreign = tasks.create(stranger.token, TaskApi.logCheck(stranger.model, "it@example.com"))
        def management = api.call(setup.token, "POST", "/tenant/admin/mcp-keys", [name: "Geschäftsleitung", taskIds: [task.id]]) as Map
        String workshop = (api.call(setup.token, "POST", "/tenant/admin/mcp-keys", [name: "Werkstatt"]) as Map).plainKey
        String strangerKey = (api.call(stranger.token, "POST", "/tenant/admin/mcp-keys", [name: "Claude", taskIds: [foreign.id]]) as Map).plainKey
        api.call(setup.token, "POST", "/tenant/admin/api-keys", [name: "ERP", taskIds: [task.id]])
        def runId = tasks.start(setup.token, task.id)
        tasks.awaitRun(setup.token, task.id, runId)

        expect: "the key with the task lists it and reads its runs, also one it did not start"
        (tool(management.plainKey as String, "list_tasks", [:]).content[0].text as String).contains("Rechnungsprüfung")
        !tool(management.plainKey as String, "task_run", [task: "Rechnungsprüfung", run: runId]).isError

        and: "another key of the tenant neither lists, starts nor reads it"
        tool(workshop, "list_tasks", [:]).content[0].text == "No task is opened to you."
        tool(workshop, "run_task", [task: "Rechnungsprüfung"]).isError
        tool(workshop, "task_run", [task: "Rechnungsprüfung", run: runId]).isError
        !(tool(strangerKey, "list_tasks", [:]).content[0].text as String).contains("Rechnungsprüfung")

        and: "another tenant's task is refused, a change replaces the tasks, a rename keeps them"
        tasks.refusal(setup.token, "POST", "/tenant/admin/mcp-keys", [name: "X", taskIds: [foreign.id]]) == [400, "mcpKey.unknownTask"]
        tasks.refusal(setup.token, "PUT", "/tenant/admin/mcp-keys/${management.key.id}", [taskIds: [foreign.id]]) == [400, "mcpKey.unknownTask"]
        (api.call(setup.token, "PUT", "/tenant/admin/mcp-keys/${management.key.id}", [name: "GL"]) as Map).taskIds == [task.id]

        and: "the task names the keys that may start it"
        (api.call(setup.token, "GET", "${TASKS}/${task.id}") as Map).keys == [[kind: "API", name: "ERP"], [kind: "MCP", name: "GL"]]
        (api.call(setup.token, "GET", "/tenant/admin/mcp-keys") as List<Map>).find { it.name == "GL" }.taskIds == [task.id]

        when: "the key's tasks emptied"
        api.call(setup.token, "PUT", "/tenant/admin/mcp-keys/${management.key.id}", [taskIds: []])

        then:
        tool(management.plainKey as String, "run_task", [task: "Rechnungsprüfung"]).isError
        (api.call(setup.token, "GET", "${TASKS}/${task.id}") as Map).keys == [[kind: "API", name: "ERP"]]
    }

    def "run_task is refused while the admin has the tenant's tasks switched off - no run is made; the key still lists its tasks"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, "it@example.com", [name: "Abgeschaltet"]))
        String key = (api.call(setup.token, "POST", "/tenant/admin/mcp-keys", [name: "Claude", taskIds: [task.id]]) as Map).plainKey
        tasks.allowTasks(setup.tenant, false)

        when:
        Map started = tool(key, "run_task", [task: "Abgeschaltet"])

        then:
        started.isError
        (started.content[0].text as String).contains("not enabled")
        (tool(key, "list_tasks", [:]).content[0].text as String).contains("Abgeschaltet")
        api.admin("GET", "/admin/tenants/${setup.tenant.id}/tasks/${task.id}/runs") == []
    }

    def "a run that takes longer answers with its id - task_run asks for it later"() {
        given:
        def setup = tasks.tenant()
        store.write(log(), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, "it@example.com", [name: "Langsam"]))
        String key = (api.call(setup.token, "POST", "/tenant/admin/mcp-keys", [name: "Claude", taskIds: [task.id]]) as Map).plainKey
        store.hold = new CountDownLatch(1)

        when: "the spec's wait is 3 s (app.task.mcp-wait)"
        String waiting = tool(key, "run_task", [task: "Langsam"]).content[0].text
        Long runId = (waiting =~ /run (\d+)\)/)[0][1] as Long
        store.hold.countDown()
        tasks.awaitRun(setup.token, task.id, runId)
        String ended = tool(key, "task_run", [task: "Langsam", run: runId]).content[0].text

        then:
        waiting.contains("is still RUNNING")
        ended.contains("ended DONE")
    }

    def "a cancelled run ends before its next step - no mail, the lines read again by the next run"() {
        given:
        def setup = tasks.tenant()
        String notify = "it-" + UUID.randomUUID() + "@example.com"
        store.write(log(), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, notify, [notify: notify]))
        store.hold = new CountDownLatch(1)
        def runId = tasks.start(setup.token, task.id)

        when:
        api.status(setup.token, "POST", "${TASKS}/${task.id}/runs/${runId}/cancel")
        store.hold.countDown()
        def cancelled = tasks.awaitRun(setup.token, task.id, runId)

        then:
        (cancelled.run as Map).state == "CANCELLED"
        !((cancelled.steps as List<Map>)*.stepId).contains("check")
        TaskApi.mailsTo(notify).isEmpty()
        tasks.refusal(setup.token, "POST", "${TASKS}/${task.id}/runs/${runId}/cancel") == [409, "task.runEnded"]

        when:
        def next = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        ((next.steps as List<Map>)[0].result as Map).lines == 1
    }

    /** The log check with one schedule. */
    private static String scheduled(Map model, String schedule) {
        TaskApi.logCheck(model, "it@example.com").replace("steps:\n", "triggers:\n  - type: schedule\n    ${schedule}\nsteps:\n")
    }

    /** Makes the task due now (seconds only, as the database keeps it) and returns that time. */
    private Instant dueNow(Long taskId) {
        Instant due = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        setNextRun(taskId, due)
        due
    }

    private void setNextRun(Long taskId, Instant at) {
        def task = taskRepository.findById(taskId).get()
        taskRepository.update(new de.alpdesk.entities.task.Task(task.id(), task.tenantId(), task.name(), task.source(),
                task.definition(), task.version(), task.active(), at, task.createdAt(), task.updatedAt()))
    }

    private Map awaitApiRun(String apiKey, Object taskId, Object runId) {
        long deadline = System.currentTimeMillis() + 20_000
        while (true) {
            Map run = api.call(apiKey, "GET", "/api/v1/tasks/${taskId}/runs/${runId}") as Map
            if (!(run.state in ["QUEUED", "RUNNING"]) || System.currentTimeMillis() > deadline) {
                return run
            }
            Thread.sleep(100)
        }
    }

    /** Waits until the run recorded the step as done. */
    private void awaitStep(String token, Object taskId, Object runId, String stepId) {
        long deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            Map run = api.call(token, "GET", "${TASKS}/${taskId}/runs/${runId}") as Map
            if ((run.steps as List<Map>).any { it.stepId == stepId && it.state == "DONE" }) {
                return
            }
            Thread.sleep(100)
        }
        throw new AssertionError("The step $stepId of run $runId did not end")
    }

    /** A tool call on the tenant's MCP server: its result. */
    private Map tool(String key, String name, Map arguments) {
        Map message = [jsonrpc: "2.0", id: UUID.randomUUID().toString(), method: "tools/call",
                       params : [name: name, arguments: arguments]]
        try {
            (client.toBlocking().exchange(HttpRequest.POST("/mcp", message).bearerAuth(key), Map).body() as Map).result as Map
        } catch (HttpClientResponseException e) {
            throw new AssertionError("tools/call $name answered ${e.status}: ${e.response.getBody(String).orElse('')}")
        }
    }

    private static String log() {
        LOGS + "/" + LocalDate.now(ZoneId.of("Europe/Berlin")) + "_app.log"
    }
}
