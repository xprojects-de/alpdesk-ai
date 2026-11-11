package de.alpdesk.services.task

import de.alpdesk.entities.task.RunState
import de.alpdesk.entities.task.StartedBy
import de.alpdesk.entities.task.TaskRun
import de.alpdesk.entities.tenant.Tenant
import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.repositories.task.TaskRepository
import de.alpdesk.repositories.task.TaskRunRepository
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.Spent
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.task.step.ReadFilesStep
import de.alpdesk.services.tenant.TenantDatabases
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.json.JsonMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.bsc.langgraph4j.GraphInput
import org.bsc.langgraph4j.RunnableConfig
import org.bsc.langgraph4j.checkpoint.PostgresSaver
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException

import static de.alpdesk.services.task.TaskApi.LOGS
import static de.alpdesk.services.task.TaskApi.TASKS

/**
 * Running a task (docs/architecture/tasks.md): the new lines of the day's logs read from the store, checked by the
 * tenant's model, verified, mailed only on findings - a failed or unclear run mails the notify list. Read positions move
 * on only after a successful run; one run of a task at a time; a run left open goes on at the start. The model is
 * FakeAiModels (its task result), the store FakeStoreClient, the mails GreenMail.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
class TaskRunSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels models

    @Inject
    FakeStoreClient store

    @Inject
    TaskRunner runner

    @Inject
    TaskRunRepository runs

    @Inject
    TenantRepository tenants

    @Inject
    TenantDatabases databases

    @Inject
    TaskRepository taskRepository

    @Inject
    TaskService taskService

    @Inject
    TaskRunCleanupJob runCleanup

    @Inject
    TransactionOperations<Connection> transactions

    @Inject
    ReadFilesStep readFiles

    @Inject
    JsonMapper json

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

    def "a run finds the timeouts, verifies them and mails them with their lines"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        store.write(log("app"), "INFO start\nERROR Timeout to interface X\nWARN slow\nERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, mail))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "every step ran, the findings counted on the evidence"
        (run.run as Map).state == "DONE"
        (run.run as Map).inputTokens > 0
        (run.steps as List<Map>).collect { [it.stepId, it.state] } ==
                [["logs", "DONE"], ["check", "DONE"], ["verify", "DONE"], ["mail", "DONE"]]
        def read = (run.steps as List<Map>)[0].result as Map
        read.files == 1
        read.lines == 3
        def verified = ((run.steps as List<Map>)[2].result as Map)
        verified.errors == 1
        ((verified.checks as List<Map>)[0].evidence as List<Map>)*.line == [2, 4]

        and: "one mail with the lines"
        def mails = TaskApi.mailsTo(mail)
        mails.size() == 1
        mails[0].subject == "Findings in the logs"
        (mails[0].content as String).contains("2: ERROR Timeout to interface X")
    }

    def "the verification is told to write what is wrong - its note is what the mail shows under a finding"() {
        given:
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))

        when:
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        String system = models.chats.collect { (it.messages as List).first().text() as String }
                .find { it.startsWith("You verify the findings") }
        system.contains("your note states the problem itself")
        system.contains("never that you checked it")
    }

    def "the next run reads only what is new - without new lines no model is asked and nothing is mailed"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, mail))
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        int asked = models.chats.size()

        when:
        def second = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (second.run as Map).state == "DONE"
        ((second.steps as List<Map>)[0].result as Map).lines == 0
        models.chats.size() == asked
        (second.steps as List<Map>).find { it.stepId == "mail" }.state == "SKIPPED"
        TaskApi.mailsTo(mail).size() == 1

        when: "new lines come, and a line still being written waits for its line break"
        store.append(log("app"), "INFO ok\nERROR Timeout to interface X\nERROR Timeout to inter")
        def third = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        ((third.steps as List<Map>)[0].result as Map).lines == 1
        (((third.steps as List<Map>)[2].result as Map).checks as List<Map>)[0].evidence*.line == [3]

        when: "a file smaller than its position was rotated: read from its start"
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def fourth = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        ((fourth.steps as List<Map>)[0].result as Map).lines == 1

        when: "rotated and grown past its old position before the next run: still read from its start"
        store.write(log("app"), "ERROR disk full on the host\nERROR Timeout to interface X\n")
        def fifth = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        ((fifth.steps as List<Map>)[0].result as Map).lines == 2

        when: "rotated and still empty: the position goes back to its start"
        store.write(log("app"), "")
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def dataSource = databases.dataSource(tenants.findById(setup.tenant.id as Long).get().databaseName())
        runs.findPositions(dataSource, task.id as Long, setup.store.id as Long)*.byteOffset() == [0L]
    }

    def "nothing found: no mail"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        store.write(log("app"), "ERROR disk full\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, mail))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        (run.steps as List<Map>).find { it.stepId == "mail" }.state == "SKIPPED"
        TaskApi.mailsTo(mail).isEmpty()
    }

    def "a run keeps what its steps cost, as the usage recorded it - the same in the run, its rounds, the list and the task list"() {
        given: "the model costs 3 per million input and 15 per million output tokens"
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        def listed = (api.call(setup.token, "GET", "${TASKS}/${task.id}/runs") as List<Map>)[0]

        then: "each round with tokens at the price, the others without a cost"
        def steps = run.steps as List<Map>
        steps.findAll { it.inputTokens > 0 }*.stepId == ["check", "verify"]
        steps.every { amount(it.cost) == (it.inputTokens > 0 ? atPrice(it, 3, 15) : null) }

        and: "the run is the sum of its rounds, the list and the task list say the same"
        def sum = steps.findAll { it.cost != null }.sum { amount(it.cost) } as BigDecimal
        amount((run.run as Map).cost) == sum
        !(run.run as Map).costPartial
        amount(listed.cost) == sum
        amount(((api.call(setup.token, "GET", TASKS) as List<Map>).find { it.id == task.id }.lastRun as Map).cost) == sum
    }

    def "a kept cost stays: a changed price or a model deleted from the catalog changes nothing of it"() {
        given:
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        def runId = tasks.start(setup.token, task.id)
        def before = tasks.awaitRun(setup.token, task.id, runId)

        when:
        api.admin("PUT", "/admin/models/${setup.model.id}", setup.model + [inputPrice: 6, outputPrice: 30])
        def changed = api.call(setup.token, "GET", "${TASKS}/${task.id}/runs/${runId}") as Map
        def deleted = api.status(api.adminToken, "DELETE", "/admin/models/${setup.model.id}")
        def gone = api.call(setup.token, "GET", "${TASKS}/${task.id}/runs/${runId}") as Map

        then:
        deleted.code == 204
        [changed, gone].every { amount((it.run as Map).cost) == amount((before.run as Map).cost) }
        [changed, gone].every { (it.steps as List<Map>)*.cost == (before.steps as List<Map>)*.cost }
        !(gone.run as Map).costPartial
    }

    def "a run from before costs were kept is computed at today's price - a deleted model leaves its steps without one"() {
        given: "a run whose kept costs are taken away, as in a run from before"
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        def runId = tasks.start(setup.token, task.id)
        tasks.awaitRun(setup.token, task.id, runId)
        def dataSource = databases.dataSource(tenants.findById(setup.tenant.id as Long).get().databaseName())
        sql(dataSource, "UPDATE task_run SET cost = NULL WHERE id = ${runId}")
        sql(dataSource, "UPDATE task_run_step SET cost = NULL WHERE run_id = ${runId}")

        when: "the admin doubles the prices and adds a tier far below the step's summed tokens"
        api.admin("PUT", "/admin/models/${setup.model.id}", setup.model + [inputPrice: 6, outputPrice: 30,
                tierThreshold: 10, tierInputPrice: 100, tierOutputPrice: 100])
        def run = api.call(setup.token, "GET", "${TASKS}/${task.id}/runs/${runId}") as Map

        then: "at today's lower prices - which of the step's calls was above the threshold is not known"
        (run.steps as List<Map>).findAll { it.inputTokens > 0 }.every { amount(it.cost) == atPrice(it, 6, 30) }
        ((api.call(setup.token, "GET", TASKS) as List<Map>).find { it.id == task.id }.lastRun as Map).cost == null

        when: "the model goes from the catalog"
        api.status(api.adminToken, "DELETE", "/admin/models/${setup.model.id}")
        run = api.call(setup.token, "GET", "${TASKS}/${task.id}/runs/${runId}") as Map
        def listed = (api.call(setup.token, "GET", "${TASKS}/${task.id}/runs") as List<Map>)[0]

        then: "no step has a cost, the run's is 0 and marked partial"
        (run.steps as List<Map>).every { it.cost == null }
        amount((run.run as Map).cost) == 0
        (run.run as Map).costPartial
        listed.costPartial
    }

    def "a step that fails keeps the tokens and the cost of its calls - they are in the usage too"() {
        given: "a check whose model answers twice with no result"
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        models.brokenTaskAnswers = 2

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        def usage = api.call(setup.token, "GET", "/tenant/admin/usage") as List<Map>

        then:
        (run.run as Map).state == "FAILED"
        def check = (run.steps as List<Map>).find { it.stepId == "check" }
        check.state == "FAILED"
        check.inputTokens > 0
        amount(check.cost) == atPrice(check, 3, 15)
        (run.run as Map).inputTokens == check.inputTokens
        amount((run.run as Map).cost) == amount(check.cost)
        usage*.inputTokens == [check.inputTokens]
        amount(usage[0].amount) == amount(check.cost)
    }

    def "a run lands in the tenant's usage - every round's call with its tokens and amount, on the tenant's own key"() {
        given: "a tenant with nothing used yet"
        def setup = tasks.tenant()
        String usagePath = "/tenant/admin/usage"
        assert (api.call(setup.token, "GET", usagePath) as List).isEmpty()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        def usage = api.call(setup.token, "GET", usagePath) as List<Map>

        then: "one row for the model, with the run's tokens and its cost"
        usage.size() == 1
        usage[0].modelName == setup.model.modelName
        usage[0].keySource == "OWN"
        usage[0].calls >= 2
        usage[0].inputTokens == (run.run as Map).inputTokens
        usage[0].outputTokens == (run.run as Map).outputTokens
        amount(usage[0].amount) == amount((run.run as Map).cost)

        and: "the admin sees the same for the tenant"
        def admin = api.admin("GET", "/admin/usage?tenantId=${setup.tenant.id}") as List<Map>
        admin*.inputTokens == [(run.run as Map).inputTokens]
        amount(admin[0].amount) == amount((run.run as Map).cost)
    }

    def "a run that asked no model costs nothing - and is not partial"() {
        given:
        def setup = tasks.tenant()
        store.write(log("app"), "")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        amount((run.run as Map).cost) == 0
        !(run.run as Map).costPartial
    }

    def "two files of one name from two folders stay two - the evidence names where its line comes from"() {
        given:
        def setup = tasks.tenant()
        store.write(LOGS + "/a/server.log", "ERROR disk full\n")
        store.write(LOGS + "/b/server.log", "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, """\
            version: 1
            name: Two servers
            notify: [${recipient()}]
            steps:
              - id: first
                type: read_files
                store: Logdateien
                folder: a
                pattern: server.log
              - id: second
                type: read_files
                store: Logdateien
                folder: b
                pattern: server.log
              - id: check
                type: agent
                model: ${setup.model.displayName}
                input: [first, second]
                checks:
                  - name: Interface
                    severity: error
                    instruction: Were there timeouts to interface X? [[find Timeout]]
            """.stripIndent())

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def checked = (run.steps as List<Map>).find { it.stepId == "check" }.result as Map
        ((checked.checks as List<Map>)[0].evidence as List<Map>).collect { [it.file, it.line] } ==
                [["Logdateien:b/server.log", 1]]
    }

    def "a verification unclear goes back to the check, which sees why - then confirmed and mailed"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, mail, [checkMarker: " [[unclear-until 1]]"]))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        (run.steps as List<Map>).collect { [it.stepId, it.round] } ==
                [["logs", 1], ["check", 1], ["verify", 1], ["check", 2], ["verify", 2], ["mail", 1]]
        models.chats.findAll { it.messages.any { m -> m.toString().contains("=== Notes of the verification") } }.size() == 1
        TaskApi.mailsTo(mail).size() == 1
    }

    def "unclear when the edge back reached its limit: the notify list hears of it, never 'nothing found'"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        String notify = recipient()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, mail,
                [notify: notify, verifyRounds: 1, checkMarker: " [[unclear-until 9]]"]))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "UNCLEAR"
        TaskApi.mailsTo(mail).isEmpty()
        TaskApi.mailsTo(notify)*.subject == [task.name + ": findings unclear"]
    }

    def "a line the model made up is no evidence - its finding counts as unclear"() {
        given:
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient(),
                [find: "nichts", checkMarker: " [[invent]]", verifyRounds: 1]))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def check = (((run.steps as List<Map>)[1].result as Map).checks as List<Map>)[0]
        check.status == "unclear"
        check.evidence == []
        (check.note as String).contains("no line of the files shows it")
    }

    def "an answer that is no result gets one more round - a second one fails the run, and the notify list hears why"() {
        given:
        def setup = tasks.tenant()
        String notify = recipient()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient(), [notify: notify]))
        models.brokenTaskAnswers = broken

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == state
        (run.run as Map).errorCode == code
        TaskApi.mailsTo(notify)*.subject == (failureMail ? [task.name + ": run failed"] : [])

        where:
        broken || state    | code                | failureMail
        1      || "DONE"   | null                | false
        2      || "FAILED" | "task.resultInvalid" | true
    }

    def "a store out of reach fails the run - the lines are read again by the next run"() {
        given:
        def setup = tasks.tenant()
        String notify = recipient()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient(), [notify: notify]))
        store.unreachable = true

        when:
        def failed = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (failed.run as Map).state == "FAILED"
        (failed.run as Map).errorCode == "fileStore.unreachable"
        (failed.steps as List<Map>).collect { [it.stepId, it.state, it.errorCode] } == [["logs", "FAILED", "fileStore.unreachable"]]
        TaskApi.mailsTo(notify)*.subject == [task.name + ": run failed"]

        when:
        store.unreachable = false
        def next = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        ((next.steps as List<Map>)[0].result as Map).lines == 1
    }

    def "a store that fails a run - its folder gone, another key presented - fails it with its code, and the notify list hears why"() {
        given:
        def setup = tasks.tenant()
        String notify = recipient()
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient(), [notify: notify]))
        if (broken == "folder gone") {
            store.folders.clear()
        } else {
            store.presented = "SHA256:" + "A" * 43
        }

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "FAILED"
        (run.run as Map).errorCode == code
        (run.steps as List<Map>).collect { [it.stepId, it.state, it.errorCode] } == [["logs", "FAILED", code]]
        TaskApi.mailsTo(notify)*.subject == [task.name + ": run failed"]

        where:
        broken          || code
        "folder gone"   || "fileStore.folderNotFound"
        "another key"   || "fileStore.fingerprintChanged"
    }

    def "a task reads below the store's folder as it is when it runs - a store moved to another folder takes its tasks along"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        store.write("/archiv" + log("app").substring(LOGS.length()), "ERROR Timeout to interface X\n")

        when: "the store is moved to another folder on the same server, keeping password and fingerprint"
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}/file-stores/${setup.store.id}", [name: "Logdateien",
                type: "SFTP", host: "localhost", port: 22, username: "check", password: null, basePath: "/archiv"])
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        ((run.steps as List<Map>)[0].result as Map).lines == 1
    }

    def "a check point left unclear ends the run unclear without an edge back - the notify list hears of it, though no mail was due"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        String notify = recipient()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, """\
            version: 1
            name: Ohne Rückweg ${UUID.randomUUID()}
            notify: [${notify}]
            steps:
              - id: logs
                type: read_files
                store: Logdateien
                pattern: "{yyyy-MM-dd}_*.log"
              - id: check
                type: agent
                model: ${setup.model.displayName}
                input: [logs]
                checks:
                  - name: Interface
                    severity: error
                    instruction: Were there timeouts to interface X? [[invent]]
              - id: mail
                type: send_mail
                if: check.errors > 0
                input: [check]
                to: [${mail}]
                subject: Findings in the logs
            """.stripIndent())

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "the made-up line is no finding, the mail on errors is skipped - the run is not 'nothing found'"
        (run.run as Map).state == "UNCLEAR"
        (run.steps as List<Map>).find { it.stepId == "mail" }.state == "SKIPPED"
        TaskApi.mailsTo(mail).isEmpty()
        def unclear = TaskApi.mailsTo(notify)
        unclear*.subject == [task.name + ": findings unclear"]
        (unclear[0].content as String).contains("no line of the files shows it")
    }

    def "one run of a task at a time - an open run cannot be deleted, nor its task"() {
        given:
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        store.hold = new CountDownLatch(1)
        def runId = tasks.start(setup.token, task.id)

        expect:
        tasks.refusal(setup.token, "POST", "${TASKS}/${task.id}/runs") == [409, "task.running"]
        tasks.refusal(setup.token, "DELETE", "${TASKS}/${task.id}/runs/${runId}") == [409, "task.runOpen"]
        tasks.refusal(setup.token, "DELETE", "${TASKS}/${task.id}") == [409, "task.running"]

        when:
        store.hold.countDown()
        tasks.awaitRun(setup.token, task.id, runId)

        then:
        api.status(setup.token, "DELETE", "${TASKS}/${task.id}/runs/${runId}").code == 204
        api.call(setup.token, "GET", "${TASKS}/${task.id}/runs") == []
    }

    def "a switched-off task does not start"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()), false)

        expect:
        tasks.refusal(setup.token, "POST", "${TASKS}/${task.id}/runs") == [409, "task.inactive"]
    }

    def "a run left open goes on at the start - unless the tenant may no longer run tasks"() {
        given:
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        def tenant = tenants.findById(setup.tenant.id as Long).get()
        def dataSource = databases.dataSource(tenant.databaseName())

        when: "a run was waiting when the server stopped"
        def open = runs.create(dataSource, task.id as Long, 1, definition(task.id as Long), StartedBy.TENANT_ADMIN, null, null).get()
        runner.onApplicationEvent(null)
        def run = tasks.awaitRun(setup.token, task.id, open.id())

        then:
        (run.run as Map).state == "DONE"

        when: "the admin took the switch away meanwhile"
        def waiting = runs.create(dataSource, task.id as Long, 1, definition(task.id as Long), StartedBy.TENANT_ADMIN, null, null).get()
        tasks.allowTasks(setup.tenant, false)
        runner.onApplicationEvent(null)
        def ended = awaitEnd(dataSource, task.id as Long, waiting.id())

        then:
        ended.state() == RunState.FAILED
        ended.errorCode() == "task.notAllowed"
    }

    def "a run stopped after a step goes on from its checkpoint at the start - the step done is not run again"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        store.write(log("app"), "INFO start\nERROR Timeout to interface X\nWARN slow\nERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, mail))
        def tenant = tenants.findById(setup.tenant.id as Long).get()
        def dataSource = databases.dataSource(tenant.databaseName())
        def open = runs.create(dataSource, task.id as Long, 1, definition(task.id as Long), StartedBy.TENANT_ADMIN, null, null).get()

        and: "the server stopped after the logs were read, before the check"
        stopAfterFirstStep(tenant, dataSource, open)

        expect: "langgraph4j's checkpoint holds the state as JSON text"
        def state = json.readValue(lastCheckpointState(dataSource, open.id()), Map)
        (state.results as Map).keySet() == ["logs"] as Set
        ((state.results as Map).logs as Map).lines == 3

        when:
        runner.onApplicationEvent(null)
        def run = tasks.awaitRun(setup.token, task.id, open.id())

        then: "the logs were not read again - the check got their lines from the checkpoint"
        (run.run as Map).state == "DONE"
        (run.steps as List<Map>).collect { [it.stepId, it.state] } == [["check", "DONE"], ["verify", "DONE"], ["mail", "DONE"]]
        def verified = (run.steps as List<Map>)[1].result as Map
        verified.errors == 1
        ((verified.checks as List<Map>)[0].evidence as List<Map>)*.line == [2, 4]
        TaskApi.mailsTo(mail).size() == 1
        checkpoints(dataSource, open.id()) == 0
    }

    def "a step's model gets only search_file and read_file_lines - never the company knowledge, though the tenant has it"() {
        given: "a tenant whose chat would search its company knowledge, and a log too large to go with the question whole"
        def setup = tasks.tenant()
        def embedding = api.embeddingModel()
        api.allow(setup.tenant.id, embedding)
        api.call(setup.token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        String line = "ERROR Timeout to interface X while sending order " + ("0123456789" * 6) + "\n"
        store.write(log("app"), line * 1200)
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        !["QUEUED", "RUNNING"].contains((run.run as Map).state)
        def stepCalls = models.chats.findAll { stepSystem(it.messages as List)?.startsWith("You check files")
                || stepSystem(it.messages as List)?.startsWith("You verify") }
        !stepCalls.isEmpty()
        stepCalls.every { (it.listedTools as List).toSet() == ["search_file", "read_file_lines"] as Set }
    }

    def "firstRun: end - the first run reads nothing and starts after the last complete line; a file new later is read whole"() {
        given: "a log with a past, its last line still being written"
        def setup = tasks.tenant()
        String mail = recipient()
        store.write(log("app"), "ERROR Timeout to interface X\nERROR Timeout to interface X\nERROR Timeout to inter")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, mail).replace("days: 2", "days: 2\n    firstRun: end"))

        when:
        def first = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "nothing read, no model asked, no mail"
        (first.run as Map).state == "DONE"
        ((first.steps as List<Map>)[0].result as Map).lines == 0
        models.chats.isEmpty()
        TaskApi.mailsTo(mail).isEmpty()

        when: "the line being written ends, a new one comes, and a file appears that the first run did not see"
        store.append(log("app"), "face X\nINFO ok\n")
        store.write(log("other"), "ERROR Timeout to interface X\n")
        def second = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "the line being written comes whole, the new file from its start - its numbers count from where each began"
        def read = (second.steps as List<Map>)[0].result as Map
        read.files == 2
        read.lines == 2
        (((second.steps as List<Map>)[2].result as Map).checks as List<Map>)[0].evidence*.line == [1, 1]
    }

    def "firstRun: end - a first run that finds no file yet is over all the same: the file that comes later is read whole"() {
        given: "a task set up before the client software wrote its first log"
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()).replace("days: 2", "days: 2\n    firstRun: end"))

        when:
        def first = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (first.run as Map).state == "DONE"
        ((first.steps as List<Map>)[0].result as Map).files == 0

        when: "the log appears with its first lines"
        store.write(log("app"), "ERROR Timeout to interface X\nERROR Timeout to interface X\n")
        def second = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "they are read, not skipped by a second start at the end"
        def read = (second.steps as List<Map>)[0].result as Map
        read.files == 1
        read.lines == 2
    }

    def "a report check point is handed on as written: the verification is not asked about it, it counts as reports and goes into the mail"() {
        given: "a check for timeouts and a report of every error"
        def setup = tasks.tenant()
        String mail = recipient()
        store.write(log("app"), "ERROR Timeout to interface X\nERROR disk full\n")
        def task = tasks.create(setup.token, reportTask(setup.model.displayName as String, mail,
                "verify.errors > 0 || verify.reports > 0"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "the check counts one error and one report"
        (run.run as Map).state == "DONE"
        def checked = (run.steps as List<Map>).find { it.stepId == "check" }.result as Map
        checked.errors == 1
        checked.reports == 1
        String asked = stepQuestion("You check files")
        asked.contains('=== Check point "Errors" (report) ===')
        asked.contains("A report, not a question")
        String system = models.chats.collect { stepSystem(it.messages as List) }.find { it?.startsWith("You check files") }
        system.contains("at most 20 per check point")
        system.contains("a report: the report itself")

        and: "the verification judges only the timeout - the report stays as the check wrote it, in its place"
        String verifying = stepQuestion("You verify")
        verifying.contains('Check point "Interface"')
        !verifying.contains('Check point "Errors"')
        def verified = (run.steps as List<Map>).find { it.stepId == "verify" }.result as Map
        verified.errors == 1
        verified.reports == 1
        (verified.checks as List<Map>)*.name == ["Interface", "Errors"]
        ((verified.checks as List<Map>)[1].evidence as List<Map>)*.line == [1, 2]

        and: "the mail shows it as a report"
        def mails = TaskApi.mailsTo(mail)
        mails.size() == 1
        (mails[0].content as String).contains("Errors (report)")
    }

    def "only reports: no verification is asked, and a report alone sends the mail when the condition says so"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        store.write(log("app"), "ERROR disk full\n")
        def task = tasks.create(setup.token, reportTask(setup.model.displayName as String, mail, "verify.reports > 0", false))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        models.chats.count { stepSystem(it.messages as List)?.startsWith("You verify") } == 0
        ((run.steps as List<Map>).find { it.stepId == "verify" }.result as Map).reports == 1
        TaskApi.mailsTo(mail).size() == 1
    }

    def "minLines: a file that did not grow fails the run, and the notify list hears why - not on a first run that starts at the end"() {
        given:
        def setup = tasks.tenant()
        String notify = recipient()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient(), [notify: notify])
                .replace("days: 2", "days: 2\n    firstRun: end\n    minLines: 1"))

        when: "the first run starts at the end on purpose"
        def first = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (first.run as Map).state == "DONE"

        when: "a line comes, then none"
        store.append(log("app"), "ERROR Timeout to interface X\n")
        def second = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        def third = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "the run without new lines fails at the step, with its code - and the notify list is told"
        (second.run as Map).state == "DONE"
        (third.run as Map).state == "FAILED"
        (third.run as Map).errorCode == "task.tooFewLines"
        ((third.steps as List<Map>).find { it.state == "FAILED" }.result as Map).errorParams == [step: "logs", lines: "0", min: "1"]
        TaskApi.mailsTo(notify).any { (it.subject as String).endsWith(": run failed") && (it.content as String).contains("at least 1 expected") }
    }

    def "masked values reach neither the model nor the mail - also one at the line's cut"() {
        given: "a password in a short line, and a token right where a long line is cut at 2000 characters"
        def setup = tasks.tenant()
        String mail = recipient()
        store.write(log("app"), 'ERROR Timeout to interface X ApiPassword="pw-short-0001"\n'
                + "ERROR Timeout to interface X " + "x" * 1950 + " companyToken=tok-at-the-cut-0002 end\n"
                + "ERROR Timeout to interface X login password: pw-colon-0003\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, mail).replace("days: 2",
                "days: 2\n    mask:\n      - after: 'ApiPassword=\"'\n      - after: 'password: '\n"
                        + "      - regex: 'companyToken=[a-z0-9-]+'"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "the lines are kept and checked, their values replaced"
        (run.run as Map).state == "DONE"
        ((run.steps as List<Map>)[0].result as Map).lines == 3
        String question = stepQuestion("You check files")
        question.contains('ApiPassword="***"')
        question.contains("x ***")
        question.contains("password: ***")
        !question.contains("pw-short") && !question.contains("tok-at") && !question.contains("pw-colon")

        and: "the mail quotes them masked"
        def mails = TaskApi.mailsTo(mail)
        mails.size() == 1
        !(mails[0].content as String).contains("pw-short")
        !(mails[0].content as String).contains("pw-colon")
        (mails[0].content as String).contains("***")
    }

    def "a step's inputs go to its model under their steps - a step without lines is named, not left out"() {
        given: "two steps reading the same log, split by their filters - one of them keeps nothing"
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\nINFO ok\n")
        def task = tasks.create(setup.token, """\
            version: 1
            name: Split log ${UUID.randomUUID()}
            notify: [${recipient()}]
            steps:
              - id: failures
                type: read_files
                store: Logdateien
                pattern: "{yyyy-MM-dd}_*.log"
                include: [ERROR]
              - id: warnings
                type: read_files
                store: Logdateien
                pattern: "{yyyy-MM-dd}_*.log"
                include: [WARN]
              - id: check
                type: agent
                model: ${setup.model.displayName}
                input: [failures, warnings]
                checks:
                  - name: Interface
                    severity: error
                    instruction: Were there timeouts to interface X? [[find Timeout]]
            """.stripIndent())

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then: "the lines a step kept stand under its name, the step without lines says so"
        (run.run as Map).state == "DONE"
        String question = stepQuestion("You check files")
        question.contains('=== Step "failures"')
        question.indexOf('=== Step "failures"') < question.indexOf('1| ERROR Timeout to interface X')
        question.contains('=== Step "warnings" gave no lines this run')
        !question.contains("INFO ok")

        and: "it is told when it asks - the files end where they were read, the time says how long ago that was"
        question =~ /It is now \d{2}\.\d{2}\.\d{4} \d{2}:\d{2} \(Europe\/Berlin\)\./

        and: "the finding still counts on its line"
        (((run.steps as List<Map>).find { it.stepId == "check" }.result as Map).checks as List<Map>)[0].evidence*.line == [1]
    }

    def "runs past their retention go with the lines they read - a newer run, an open one and the read positions stay"() {
        given: "two ended runs, the first ended 15 days ago (retention 14 days by default)"
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        def old = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id)).run as Map
        store.append(log("app"), "ERROR Timeout to interface X\n")
        def recent = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id)).run as Map
        def tenant = tenants.findById(setup.tenant.id as Long).get()
        def dataSource = databases.dataSource(tenant.databaseName())
        sql(dataSource, "UPDATE task_run SET ended_at = now() - interval '15 days' WHERE id = ${old.id}")
        def open = runs.create(dataSource, task.id as Long, 1, definition(task.id as Long), StartedBy.TENANT_ADMIN, null, null).get()
        sql(dataSource, "UPDATE task_run SET ended_at = now() - interval '30 days' WHERE id = ${open.id()}")

        when:
        runCleanup.deleteEnded(Instant.now())

        then: "the old run is gone with its steps and the lines it read"
        tasks.refusal(setup.token, "GET", "${TASKS}/${task.id}/runs/${old.id}")[0] == 404
        count(dataSource, "SELECT count(*) FROM task_run_file WHERE run_id = ${old.id}") == 0
        count(dataSource, "SELECT count(*) FROM task_run_step WHERE run_id = ${old.id}") == 0

        and: "the recent run, the open one and the read positions stay"
        (tasks.awaitRun(setup.token, task.id, recent.id).run as Map).state == "DONE"
        count(dataSource, "SELECT count(*) FROM task_run WHERE id = ${open.id()}") == 1
        count(dataSource, "SELECT count(*) FROM task_read_position") == 1

        cleanup:
        sql(dataSource, "DELETE FROM task_run WHERE id = ${open.id()}")
    }

    def "the retention keeps a task's last successful run - {lastRun} of a task running less often than it stays"() {
        given: "a monthly task: its last successful run and a failed one after it, both past the retention"
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        def done = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id)).run as Map
        store.append(log("app"), "ERROR Timeout to interface X\n")
        def failed = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id)).run as Map
        def dataSource = databases.dataSource(tenants.findById(setup.tenant.id as Long).get().databaseName())
        sql(dataSource, "UPDATE task_run SET state = 'FAILED' WHERE id = ${failed.id}")
        sql(dataSource, "UPDATE task_run SET started_at = now() - interval '31 days', ended_at = now() - interval '31 days' "
                + "WHERE id = ${done.id}")
        sql(dataSource, "UPDATE task_run SET ended_at = now() - interval '20 days' WHERE id = ${failed.id}")
        Instant lastRun = runs.lastSuccessfulStart(dataSource, task.id as Long, -1L)

        when:
        runCleanup.deleteEnded(Instant.now())

        then: "the failed run goes, the successful one stays - and with it {lastRun}"
        count(dataSource, "SELECT count(*) FROM task_run WHERE id = ${failed.id}") == 0
        count(dataSource, "SELECT count(*) FROM task_run WHERE id = ${done.id}") == 1
        lastRun != null
        runs.lastSuccessfulStart(dataSource, task.id as Long, -1L) == lastRun
    }

    def "a run submitted twice - by its start and by the start's scan of open runs - is carried out once"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, mail))
        def tenant = tenants.findById(setup.tenant.id as Long).get()
        def dataSource = databases.dataSource(tenant.databaseName())
        store.hold = new CountDownLatch(1)

        when: "the request's start submits it, and the scan finds it open while it reads"
        def open = runs.create(dataSource, task.id as Long, 1, definition(task.id as Long), StartedBy.TENANT_ADMIN, null, null).get()
        runner.submit(tenant.id(), open)
        runner.submit(tenant.id(), open)
        runner.onApplicationEvent(null)
        store.hold.countDown()
        def run = tasks.awaitRun(setup.token, task.id, open.id())

        then: "one run, one mail"
        (run.run as Map).state == "DONE"
        (run.steps as List<Map>).collect { [it.stepId, it.round] } == [["logs", 1], ["check", 1], ["verify", 1], ["mail", 1]]
        TaskApi.mailsTo(mail).size() == 1
    }

    def "runs of two tenants with the same id - every tenant's database counts from 1 - are both carried out"() {
        given:
        def first = tasks.tenant()
        def second = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def taskOfFirst = tasks.create(first.token, TaskApi.logCheck(first.model, recipient()))
        def taskOfSecond = tasks.create(second.token, TaskApi.logCheck(second.model, recipient()))
        store.hold = new CountDownLatch(1)

        when: "both start while the first still reads"
        def runOfFirst = tasks.start(first.token, taskOfFirst.id)
        def runOfSecond = tasks.start(second.token, taskOfSecond.id)
        store.hold.countDown()

        then:
        runOfFirst == runOfSecond
        (tasks.awaitRun(first.token, taskOfFirst.id, runOfFirst).run as Map).state == "DONE"
        (tasks.awaitRun(second.token, taskOfSecond.id, runOfSecond).run as Map).state == "DONE"
    }

    def "a run that breaks off outside its steps is logged - it stays open for the next start, can be submitted again, and a cancel ends it"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        def tenant = tenants.findById(setup.tenant.id as Long).get()
        def dataSource = databases.dataSource(tenant.databaseName())
        def broken = runs.create(dataSource, task.id as Long, 1, "not a definition", StartedBy.TENANT_ADMIN, null, null).get()

        when:
        runner.submit(tenant.id(), broken)
        def logged = awaitLog("Run ${broken.id()} of task ${task.id} of tenant ${tenant.id()} broke off")

        then:
        logged.level() == "ERROR"
        runs.findById(dataSource, task.id as Long, broken.id()).get().state().open()

        when: "taken up again, it is not refused as submitted already"
        runner.submit(tenant.id(), broken)

        then: "still open, it blocks every start of its task"
        awaitLogs("Run ${broken.id()} of task ${task.id} of tenant ${tenant.id()} broke off", 2).size() == 2
        tasks.refusal(setup.token, "POST", "${TASKS}/${task.id}/runs") == [409, "task.running"]

        when: "cancelled, it ends at once - no thread carries it out any more"
        RunState state = null
        // the thread broke off is still about to let go of the run for a moment after its log line
        for (int attempt = 0; attempt < 50 && state != RunState.CANCELLED; attempt++) {
            api.status(setup.token, "POST", "${TASKS}/${task.id}/runs/${broken.id()}/cancel")
            state = runs.findById(dataSource, task.id as Long, broken.id()).get().state()
            if (state != RunState.CANCELLED) {
                sleep(100)
            }
        }

        then:
        state == RunState.CANCELLED
        (tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id)).run as Map).state == "DONE"
    }

    def "deleting a task deletes its runs and read positions in the tenant database"() {
        given:
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))
        def dataSource = databases.dataSource(tenants.findById(setup.tenant.id as Long).get().databaseName())

        def runId = runs.findByTask(dataSource, task.id as Long, 10, 0).first().id()
        leaveCheckpoint(dataSource, runId)

        when:
        api.status(setup.token, "DELETE", "${TASKS}/${task.id}")

        then:
        runs.findByTask(dataSource, task.id as Long, 10, 0).isEmpty()
        runs.findPositions(dataSource, task.id as Long, setup.store.id as Long).isEmpty()
        checkpoints(dataSource, runId) == 0
    }

    def "a run's checkpoints are gone at its end - and one left behind goes when the run is deleted"() {
        given:
        def setup = tasks.tenant()
        store.write(log("app"), "ERROR Timeout to interface X\n")
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        def runId = tasks.start(setup.token, task.id) as Long
        tasks.awaitRun(setup.token, task.id, runId)
        def dataSource = databases.dataSource(tenants.findById(setup.tenant.id as Long).get().databaseName())

        expect:
        checkpoints(dataSource, runId) == 0

        when:
        leaveCheckpoint(dataSource, runId)
        api.status(setup.token, "DELETE", path.replace("RUN", String.valueOf(runId)).replace("TASK", "${task.id}"))

        then:
        runs.findById(dataSource, task.id as Long, runId).isEmpty()
        checkpoints(dataSource, runId) == 0

        where:
        path << ["${TASKS}/TASK/runs/RUN", "${TASKS}/TASK/runs"]
    }

    def "a run started while its task is being deleted finds the task gone - no run is left behind"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, recipient()))
        Long tenantId = setup.tenant.id as Long
        Long taskId = task.id as Long
        def dataSource = databases.dataSource(tenants.findById(tenantId).get().databaseName())
        CompletableFuture start = null

        when: "the start comes in while the deletion is not yet committed"
        transactions.executeWrite {
            assert taskService.delete(tenantId, StartedBy.ADMIN, taskId)
            start = CompletableFuture.supplyAsync { taskService.start(tenantId, StartedBy.ADMIN, taskId, null) }
            Thread.sleep(500)
            assert !start.isDone(), "the start did not wait for the deletion"
        }
        start.get()

        then:
        def e = thrown(ExecutionException)
        (e.cause as ServiceException).code() == "task.notFound"
        runs.findByTask(dataSource, taskId, 10, 0).isEmpty()
    }

    /** The task's compiled definition, as a run keeps it. */
    private String definition(Long taskId) {
        taskRepository.findById(taskId).get().definition()
    }

    /** A checkpoint of the run as langgraph4j keeps it - as if deleting it at the run's end had failed. */
    private static void leaveCheckpoint(DataSource dataSource, Long runId) {
        dataSource.connection.withCloseable { connection ->
            def thread = UUID.randomUUID()
            def insertThread = connection.prepareStatement("INSERT INTO lg4jthread (thread_id, thread_name) VALUES (?, ?)")
            insertThread.setObject(1, thread)
            insertThread.setString(2, TaskRunner.thread(runId))
            insertThread.executeUpdate()
            def insertCheckpoint = connection.prepareStatement("INSERT INTO lg4jcheckpoint (checkpoint_id, thread_id, "
                    + "state_data, state_content_type) VALUES (?, ?, '{}', 'application/json')")
            insertCheckpoint.setObject(1, UUID.randomUUID())
            insertCheckpoint.setObject(2, thread)
            insertCheckpoint.executeUpdate()
        }
    }

    /**
     * The run's graph as the runner builds it, on langgraph4j's real saver: its first step read with the real step, the
     * next one broken off as by a server stopping - the checkpoint after the first step stays.
     */
    private void stopAfterFirstStep(Tenant tenant, DataSource dataSource, TaskRun run) {
        def task = json.readValue(run.definition(), TaskDefinition)
        def saver = PostgresSaver.builder()
                .datasource(dataSource)
                .createTables(false)
                .stateSerializer(new TaskGraph.Serializer())
                .build()
        def steps = new TaskGraph.Steps() {
            Map<String, Object> run(TaskDefinition.Step step, int round, Map<String, Map<String, Object>> results) {
                if (step.id() != task.steps().first().id()) {
                    throw new IllegalStateException("server stopped")
                }
                readFiles.run(new StepContext(tenant, dataSource, run.taskId(), run.id(), task, step, round, results,
                        new Spent())).result()
            }

            void skipped(TaskDefinition.Step step, int round) {
            }
        }
        def config = RunnableConfig.builder().threadId(TaskRunner.thread(run.id())).build()
        try {
            TaskGraph.compile(task, steps, saver).invoke(GraphInput.args([:]), config)
        } catch (Exception ignored) {
        }
    }

    private static String lastCheckpointState(DataSource dataSource, Long runId) {
        dataSource.connection.withCloseable { connection ->
            def statement = connection.prepareStatement("SELECT c.state_data FROM lg4jcheckpoint c "
                    + "JOIN lg4jthread t ON t.thread_id = c.thread_id WHERE t.thread_name = ? ORDER BY c.saved_at DESC LIMIT 1")
            statement.setString(1, TaskRunner.thread(runId))
            def result = statement.executeQuery()
            assert result.next(), "no checkpoint was left"
            result.getString(1)
        }
    }

    private static void sql(DataSource dataSource, String statement) {
        dataSource.connection.withCloseable { it.createStatement().execute(statement) }
    }

    private static int count(DataSource dataSource, String query) {
        dataSource.connection.withCloseable { connection ->
            def result = connection.createStatement().executeQuery(query)
            result.next()
            result.getInt(1)
        }
    }

    /** The run's threads and checkpoints together. */
    private static int checkpoints(DataSource dataSource, Long runId) {
        dataSource.connection.withCloseable { connection ->
            def statement = connection.prepareStatement("SELECT (SELECT count(*) FROM lg4jthread WHERE thread_name = ?) "
                    + "+ (SELECT count(*) FROM lg4jcheckpoint c JOIN lg4jthread t ON t.thread_id = c.thread_id "
                    + "WHERE t.thread_name = ?)")
            statement.setString(1, TaskRunner.thread(runId))
            statement.setString(2, TaskRunner.thread(runId))
            def result = statement.executeQuery()
            result.next()
            result.getInt(1)
        }
    }

    private def awaitEnd(javax.sql.DataSource dataSource, Long taskId, Long runId) {
        long deadline = System.currentTimeMillis() + 20_000
        while (true) {
            def run = runs.findById(dataSource, taskId, runId).get()
            if (!run.state().open() || System.currentTimeMillis() > deadline) {
                return run
            }
            Thread.sleep(100)
        }
    }

    /** The system message of a recorded model call - a task's step is known by it. */
    /** A log check with a report of every error - and, unless left out, a check for timeouts. */
    private static String reportTask(String model, String mail, String mailIf, boolean withCheck = true) {
        String interface_ = withCheck ? """
                  - name: Interface
                    severity: error
                    instruction: Were there timeouts to interface X? [[find Timeout]]""" : ""
        """\
            version: 1
            name: Report ${UUID.randomUUID()}
            notify: [${mail}]
            steps:
              - id: logs
                type: read_files
                store: Logdateien
                pattern: "{yyyy-MM-dd}_*.log"
              - id: check
                type: agent
                model: ${model}
                input: [logs]
                checks:${interface_}
                  - name: Errors
                    severity: report
                    instruction: List every error. [[find ERROR]]
              - id: verify
                type: verify
                of: check
              - id: mail
                type: send_mail
                if: ${mailIf}
                input: [verify]
            """.stripIndent()
    }

    /** The question of the first step call whose system prompt starts so. */
    private String stepQuestion(String system) {
        def messages = models.chats.collect { it.messages as List }.find { stepSystem(it)?.startsWith(system) }
        (messages.find { it instanceof UserMessage } as UserMessage).singleText()
    }

    private static String stepSystem(List messages) {
        (messages.find { it instanceof SystemMessage } as SystemMessage)?.text()
    }

    /** The runner's log lines starting so - waiting until there are at least as many as asked for. */
    private static List<RecentLogAppender.Entry> awaitLogs(String start, int atLeast) {
        long deadline = System.currentTimeMillis() + 20_000
        while (true) {
            def found = RecentLogAppender.installed().entries().findAll {
                it.logger() == TaskRunner.name && it.message().startsWith(start)
            }
            if (found.size() >= atLeast || System.currentTimeMillis() > deadline) {
                return found
            }
            Thread.sleep(100)
        }
    }

    private static RecentLogAppender.Entry awaitLog(String start) {
        def found = awaitLogs(start, 1)
        assert found.size() == 1, "the runner logged ${found.size()} lines starting '${start}'"
        found[0]
    }

    private static String log(String kind) {
        LOGS + "/" + LocalDate.now(ZoneId.of("Europe/Berlin")) + "_" + kind + ".log"
    }

    private static BigDecimal amount(Object value) {
        value == null ? null : new BigDecimal(value.toString())
    }

    /** A round's tokens at these prices per million tokens, rounded like the usage. */
    private static BigDecimal atPrice(Map step, int inputPrice, int outputPrice) {
        ((step.inputTokens as long) * inputPrice + (step.outputTokens as long) * outputPrice)
                .toBigDecimal().divide(1_000_000.toBigDecimal(), 6, java.math.RoundingMode.HALF_UP)
    }

    private static String recipient() {
        "it-" + UUID.randomUUID() + "@example.com"
    }
}
