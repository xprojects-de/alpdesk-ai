package de.alpdesk.controller.task

import de.alpdesk.repositories.task.TaskRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.task.FakeStoreClient
import de.alpdesk.services.task.TaskApi
import de.alpdesk.services.task.TaskDefinition
import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.json.JsonMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant

import static de.alpdesk.services.task.TaskApi.TASKS

/**
 * Keeping scheduled tasks (docs/architecture/tasks.md): a task is saved only when its text compiles - every mistake
 * with its line -, YAML and JSON alike; the admin's switch decides whether a tenant may have tasks; one tenant never
 * sees another's. The runs are in TaskRunSpec.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
class TaskSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels models

    @Inject
    FakeStoreClient store

    @Inject
    JsonMapper json

    @Inject
    TaskRepository taskRepository

    @Shared
    AiApi api

    @Shared
    TaskApi tasks

    def setup() {
        api = api ?: new AiApi(client)
        tasks = tasks ?: new TaskApi(api)
        models.reset()
        store.reset()
    }

    def "a tenant admin saves, reads, changes and deletes a task - the admin sees the same"() {
        given:
        def setup = tasks.tenant()
        String source = TaskApi.logCheck(setup.model, "it@example.com", [name: "Logprüfung"])

        when:
        def created = tasks.create(setup.token, source)

        then: "the text is kept as written, the steps compiled"
        created.name == "Logprüfung"
        created.version == 1
        created.active
        created.source == source
        (created.steps as List<Map>)*.id == ["logs", "check", "verify", "mail"]
        (created.steps as List<Map>)*.type == ["read_files", "agent", "verify", "send_mail"]
        (created.steps as List<Map>)[2].next == [[when: "verify.unclear > 0", to: "check", maxRounds: 2]]
        (created.steps as List<Map>)[3].when == "verify.errors > 0 || verify.warnings > 0"
        (created.steps as List<Map>)[0].line == 6
        created.lastRun == null

        and:
        (api.call(setup.token, "GET", TASKS) as List<Map>)*.name == ["Logprüfung"]
        (api.admin("GET", "/admin/tenants/${setup.tenant.id}/tasks") as List<Map>)*.id == [created.id]

        when: "it is changed"
        def changed = api.call(setup.token, "PUT", "${TASKS}/${created.id}",
                [source: source.replace("Logprüfung", "Logprüfung neu"), active: false]) as Map

        then:
        changed.name == "Logprüfung neu"
        changed.version == 2
        !changed.active

        when:
        def deleted = api.status(setup.token, "DELETE", "${TASKS}/${created.id}")

        then:
        deleted.code == 204
        api.call(setup.token, "GET", TASKS) == []
    }

    def "a definition stored before tasks had a description is still read - the scheduler and open runs read it"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, "it@example.com", [description: "Prüft die Logs."]))
        def stored = json.readValue(taskRepository.findById(task.id as Long).get().definition(), Map)

        expect:
        stored.description == "Prüft die Logs."

        when: "the same definition as it was written before: without the field"
        stored.remove("description")
        def old = json.readValue(json.writeValueAsString(stored), TaskDefinition)

        then:
        old.description() == null
        old.name() == task.name
        old.steps()*.id() == ["logs", "check", "verify", "mail"]
    }

    def "the check lists every mistake with its line - nothing is saved"() {
        given:
        def setup = tasks.tenant()

        when:
        def result = tasks.validate(setup.token, source)

        then:
        !result.valid
        result.steps == []
        def error = (result.errors as List<Map>).find { it.code == code }
        error != null
        line == null || error.line == line

        where:
        source                                                                     || code                       | line
        ""                                                                         || "task.empty"               | 0
        "version: 1\nname: [unclosed"                                              || "task.syntax"              | null
        "version: 1\nname: A\nnotify: [a@example.com]\nsteps: []\nextra: 1"        || "task.unknownField"        | 5
        "version: 1\nname: A\ndescription: [a, b]\nnotify: [a@example.com]\nsteps: []" || "task.wrongType"     | 3
        "version: 1\nname: A\ndescription: ${"x" * 501}\nnotify: [a@example.com]\nsteps: []" || "task.textTooLong" | 3
        "version: 1\nname: A\nnotify: [a@example.com]\nsteps: []\nnotify: [b@example.com]" || "task.duplicateField" | 5
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    pattern: b.log") || "task.duplicateField" | 9
        "version: 2\nname: A\nnotify: [a@example.com]\nsteps: []"                  || "task.numberInvalid"       | 1
        "version: 1\nname: A\nnotify: [kein-postfach]\nsteps: []"                  || "task.emailInvalid"        | 3
        "version: 1\nname: A\nnotify: [a@example.com]\nsteps: []"                  || "task.stepsRequired"       | 4
        "version: 1\nname: &a A\nnotify: [a@example.com]\nsteps: []"               || "task.anchor"              | 2
        step("id: Logs\n    type: read_files\n    store: X\n    pattern: a.log")   || "task.stepIdInvalid"       | 5
        step("id: x\n    type: sleep")                                             || "task.stepTypeUnknown"     | 6
        step("id: x\n    type: read_files\n    store: X\n    pattern: a/b.log")    || "task.patternInvalid"      | 8
        step("id: x\n    type: read_files\n    store: X\n    pattern: '{HH}.log'") || "task.patternInvalid"      | 8
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    folder: ../etc") || "task.folderInvalid" | 9
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    onlyNew: no") || "task.wrongType"   | 9
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    days: 40") || "task.numberInvalid"  | 9
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    firstRun: middle") || "task.firstRunUnknown" | 9
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    minLines: 0") || "task.numberInvalid" | 9
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    onlyNew: false\n    firstRun: end") || "task.firstRunWithoutOnlyNew" | 10
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    mask:\n      - {after: 'pw=', regex: 'x'}") || "task.maskInvalid" | 10
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    mask:\n      - {after: ' '}") || "task.maskInvalid" | 10
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    mask:\n      - {regex: '[a-'}") || "task.maskRegexInvalid" | 10
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    mask:\n      - {regex: 'x*'}") || "task.maskRegexEmpty" | 10
        step("id: x\n    type: read_files\n    store: X\n    pattern: a.log\n    mask:\n      - {before: 'pw='}") || "task.unknownField" | 10
        step("id: x\n    type: read_documents\n    store: X\n    pattern: a/b.pdf") || "task.documentPatternInvalid" | 8
        step("id: x\n    type: read_documents\n    store: X\n    pattern: ['*.pdf', '{yyyy}.pdf']") || "task.documentPatternInvalid" | 8
        step("id: x\n    type: read_documents\n    store: X\n    pattern: [" + (1..11).collect { "'${it}.pdf'" }.join(", ") + "]") || "task.tooMany" | 8
        step("id: x\n    type: read_documents\n    store: X\n    pattern: []") || "task.fieldRequired" | 8
        step("id: x\n    type: read_documents\n    store: X\n    pattern: '*.pdf'\n    subfolders: ja") || "task.wrongType" | 9
        step("id: x\n    type: read_documents\n    store: X\n    pattern: '*.pdf'\n    days: 2") || "task.unknownField" | 9
        step("id: x\n    type: read_documents\n    store: X\n    pattern: '*.pdf'\n    input: [y]") || "task.inputNotTaken" | 9
        step("id: x\n    type: read_documents\n    store: X\n    folder: ../etc\n    pattern: '*.pdf'") || "task.folderInvalid" | 8
        extract("    fields: []")                                              || "task.fieldsRequired"      | 13
        extract("    fields:\n      - {name: Datum, type: date}")              || "task.fieldNameInvalid"    | 14
        extract("    fields:\n      - {name: source, type: text}")             || "task.fieldNameInvalid"    | 14
        extract("    fields:\n      - {name: a, type: text}\n      - {name: a, type: date}") || "task.fieldNameTaken" | 15
        extract("    fields:\n      - {name: a, type: money}")                 || "task.fieldTypeUnknown"    | 14
        extract("    fields:\n      - {name: a, type: choice, values: [x]}")   || "task.valuesInvalid"       | 14
        extract("    fields:\n      - {name: a, type: choice}")                || "task.fieldRequired"       | 14
        extract("    fields:\n      - {name: a, type: text, values: [x, y]}")  || "task.valuesNotTaken"      | 14
        extract("    fields:\n      - {name: a, type: text, required: ja}")    || "task.wrongType"           | 14
        extract("    fields:\n      - {name: a, type: text, colour: red}")     || "task.unknownField"        | 14
        extract("    knowledge: ja\n    fields:\n      - {name: a, type: text}") || "task.wrongType"         | 13
        extract("    fields:\n      - {name: a, type: text}").replace("input: d", "input: [d, d]") || "task.inputOne" | 12
        extract("    fields:\n      - {name: a, type: text}").replace("input: d", "input: []") || "task.fieldRequired" | 12
        step("id: x\n    type: agent\n    model: M\n    input: []\n    checks:\n      - {name: A, severity: error, instruction: B}") || "task.fieldRequired" | 8
        step("id: x\n    type: extract\n    model: M\n    fields:\n      - {name: a, type: text}") || "task.fieldRequired" | 5
        writes("    file: /etc/rows.csv")                                    || "task.fileInvalid"         | 21
        writes("    file: export/../rows.csv")                               || "task.fileInvalid"         | 21
        writes("    file: export/rows.txt")                                  || "task.fileInvalid"         | 21
        writes("    file: 'export/{partner}.csv'")                           || "task.filePlaceholderInvalid" | 21
        writes("    file: 'export/{date}.csv'")                              || "task.filePlaceholderInvalid" | 21
        writes("    file: 'export/{kind:yyyy}.csv'")                         || "task.filePlaceholderInvalid" | 21
        writes("    file: 'export/{HH}.csv'")                                || "task.fileInvalid"         | 21
        writes("    file: export/rows.csv\n    mode: add")                   || "task.modeUnknown"         | 22
        writes("    file: export/rows.csv\n    separator: '|'")              || "task.separatorInvalid"    | 22
        writes("    file: export/rows.csv\n    decimal: '-'")                || "task.decimalInvalid"      | 22
        writes("    file: export/rows.csv\n    separator: ','")              || "task.dialectInvalid"      | 17
        writes("    file: export/rows.csv\n    dateFormat: 'dd.MM.yyyy HH'") || "task.dateFormatInvalid"   | 22
        writes("    file: export/rows.csv").replace("input: x", "input: d")  || "task.inputWrongType"      | 19
        step("id: x\n    type: agent\n    model: M\n    input: [nix]\n    checks:\n      - {name: A, severity: error, instruction: B}") || "task.inputUnknown" | 8
        step("id: x\n    type: agent\n    model: M\n    input: []\n    checks:\n      - {name: A, severity: no, instruction: B}") || "task.severityUnknown" | 10
        step("id: x\n    type: send_mail\n    input: [x]")                         || "task.inputUnknown"        | 7
        step("id: x\n    type: verify\n    of: y")                                 || "task.verifyOfInvalid"     | 7
        loop("verify.unclear > 0", "")                                             || "task.maxRoundsRequired"   | null
        loop("verify.unclear > 0", "\n                maxRounds: 9")                       || "task.numberInvalid"       | null
        loop("verify.unclar > 0", "\n                maxRounds: 2")                        || "task.conditionCountUnknown" | null
        loop("mail.errors > 0", "\n                maxRounds: 2")                          || "task.conditionStepUnknown" | null
        loop("verify.errors >> 0", "\n                maxRounds: 2")                       || "task.conditionInvalid"    | null
        triggers("every: 5m")                                                     || "task.everyInvalid"        | 6
        triggers("every: 8d")                                                     || "task.everyInvalid"        | 6
        triggers("every: oft")                                                    || "task.everyInvalid"        | 6
        triggers("cron: '0 */5 * * * *'")                                         || "task.scheduleTooOften"    | 6
        triggers("cron: '0 30 6 * *'")                                            || "task.cronInvalid"         | 6
        triggers("cron: '0 0 0 30 2 *'")                                          || "task.cronInvalid"         | 6
        triggers("every: 5h\n    cron: '0 0 6 * * *'")                          || "task.scheduleInvalid"     | 5
        "version: 1\nname: A\nnotify: [a@example.com]\ntriggers:\n  - type: webhook\nsteps: []" || "task.triggerTypeUnknown" | 5
    }

    def "the check finds what the tenant does not have: the store, a store ready to read, the model"() {
        given:
        def setup = tasks.tenant()
        def other = api.admin("POST", "/admin/tenants/${setup.tenant.id}/file-stores", [name: "Ungeprüft", type: "SFTP",
                host: "localhost", username: "check", password: "check-secret"]) as Map
        String source = TaskApi.logCheck(setup.model, "it@example.com")

        expect:
        (tasks.validate(setup.token, source.replace("store: Logdateien", "store: Fehlt")).errors as List<Map>)*.code == ["task.storeUnknown"]
        (tasks.validate(setup.token, source.replace("store: Logdateien", "store: Ungeprüft")).errors as List<Map>)*.code == ["task.storeNotReady"]
        (tasks.validate(setup.token, source.replace("model: ${setup.model.displayName}", "model: Unbekannt")).errors as List<Map>)*.code == ["task.modelUnknown", "task.modelUnknown"]
        other.id != null
    }

    def "JSON is a task too, read the same way - and YAML 1.1's no stays a text"() {
        given:
        def setup = tasks.tenant()
        String json = """{"version": 1, "name": "Als JSON", "notify": ["it@example.com"], "steps": [
            {"id": "logs", "type": "read_files", "store": "Logdateien", "pattern": "app.log", "onlyNew": false},
            {"id": "check", "type": "agent", "model": "${setup.model.displayName}", "input": ["logs"],
             "checks": [{"name": "no", "severity": "warning", "instruction": "Fehler?"}]}]}"""

        when:
        def created = tasks.create(setup.token, json)

        then:
        (created.steps as List<Map>)*.id == ["logs", "check"]
        created.source == json
    }

    def "saving a text that does not compile is refused with its first mistake; a name twice is refused"() {
        given:
        def setup = tasks.tenant()
        String source = TaskApi.logCheck(setup.model, "it@example.com", [name: "Doppelt"])
        tasks.create(setup.token, source)

        expect:
        tasks.refusal(setup.token, "POST", TASKS, [source: "version: 1"]) == [400, "task.invalid"]
        tasks.refusal(setup.token, "POST", TASKS, [source: source]) == [409, "task.nameTaken"]
    }

    def "without the admin's switch a tenant admin gets 409 everywhere - the admin prepares a task, but starts none"() {
        given:
        def setup = tasks.tenant(false)
        String source = TaskApi.logCheck(setup.model, "it@example.com")

        expect: "off for a new tenant - the tenant admin's account says so"
        !(api.admin("GET", "/admin/tenants/${setup.tenant.id}") as Map).tasksAllowed
        !(api.call(setup.token, "GET", "/tenant/account") as Map).tasksAllowed
        tasks.refusal(setup.token, "GET", TASKS) == [409, "task.notAllowed"]
        tasks.refusal(setup.token, "POST", TASKS, [source: source]) == [409, "task.notAllowed"]
        tasks.refusal(setup.token, "POST", "${TASKS}/validate", [source: source]) == [409, "task.notAllowed"]

        when: "the admin prepares one"
        def task = api.admin("POST", "/admin/tenants/${setup.tenant.id}/tasks", [source: source]) as Map

        then:
        tasks.refusal(api.adminToken, "POST", "/admin/tenants/${setup.tenant.id}/tasks/${task.id}/runs") == [409, "task.notAllowed"]

        when: "turned on, the tenant admin finds it and may start it"
        tasks.allowTasks(setup.tenant, true)

        then:
        (api.call(setup.token, "GET", "/tenant/account") as Map).tasksAllowed
        (api.call(setup.token, "GET", TASKS) as List<Map>)*.id == [task.id]
        tasks.start(setup.token, task.id) != null
    }

    def "one tenant never sees another's tasks"() {
        given:
        def one = tasks.tenant()
        def other = tasks.tenant()
        def task = tasks.create(one.token, TaskApi.logCheck(one.model, "it@example.com"))

        expect:
        api.call(other.token, "GET", TASKS) == []
        api.status(other.token, "GET", "${TASKS}/${task.id}").code == 404
        api.status(other.token, "PUT", "${TASKS}/${task.id}", [source: "x"]).code == 404
        api.status(other.token, "DELETE", "${TASKS}/${task.id}").code == 404
        api.status(other.token, "POST", "${TASKS}/${task.id}/runs").code == 404
        api.status(other.token, "GET", "${TASKS}/${task.id}/runs").code == 404
        api.status(api.adminToken, "GET", "/admin/tenants/${other.tenant.id}/tasks/${task.id}").code == 404
    }

    def "a file store a task reads cannot be deleted - once the task is gone it can"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, "it@example.com", [name: "Liest Logs"]))

        expect:
        tasks.refusal(setup.token, "DELETE", "/tenant/admin/file-stores/${setup.store.id}") == [409, "fileStore.inUse"]

        when:
        api.status(setup.token, "DELETE", "${TASKS}/${task.id}")

        then:
        api.status(setup.token, "DELETE", "/tenant/admin/file-stores/${setup.store.id}").code == 204
    }

    def "a file store a task reads documents from cannot be deleted either - the list shows the step's store and patterns"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, """\
            version: 1
            name: Belege
            notify: [it@example.com]
            steps:
              - id: documents
                type: read_documents
                store: Logdateien
                folder: inbox
                subfolders: true
                pattern: ["*.pdf", "*.png"]
            """.stripIndent())

        expect:
        tasks.refusal(setup.token, "DELETE", "/tenant/admin/file-stores/${setup.store.id}") == [409, "fileStore.inUse"]
        def step = ((api.call(setup.token, "GET", "${TASKS}/${task.id}") as Map).steps as List<Map>)[0]
        step.type == "read_documents"
        step.details == [store: "Logdateien", pattern: "*.pdf, *.png", subfolders: "true"]
    }

    def "a task writes only into a store whose switch lets tasks write - two tasks never write the same file"() {
        given:
        def setup = tasks.tenant()
        String source = writingTask(setup.model, "Belege A", "export/{yyyy}/rows.csv")

        expect:
        (tasks.validate(setup.token, source).errors as List<Map>)*.code == ["task.storeNotWritable"]

        when:
        tasks.allowWriting(setup)
        def first = tasks.create(setup.token, source)

        then:
        first.id != null
        tasks.refusal(setup.token, "POST", TASKS, [source: writingTask(setup.model, "Belege B", "export/{yyyy}/rows.csv")]) ==
                [409, "task.fileTaken"]
        tasks.create(setup.token, writingTask(setup.model, "Belege C", "export/{yyyy}/andere.csv")).id != null

        and: "the task keeps its own file when it is changed"
        api.call(setup.token, "PUT", "${TASKS}/${first.id}", [source: source.replace("name: Belege A", "name: Belege A2")]).name == "Belege A2"

        and: "the store it writes to cannot be deleted"
        tasks.refusal(setup.token, "DELETE", "/tenant/admin/file-stores/${setup.store.id}") == [409, "fileStore.inUse"]
    }

    private static String writingTask(Map model, String name, String file) {
        """\
        version: 1
        name: ${name}
        notify: [it@example.com]
        steps:
          - id: documents
            type: read_documents
            store: Logdateien
            pattern: '*.pdf'
          - id: rows
            type: extract
            model: ${model.displayName}
            input: documents
            fields:
              - {name: date, type: date}
          - id: csv
            type: write_file
            input: rows
            store: Logdateien
            file: '${file}'
        """.stripIndent()
    }

    def "another tenant's store a task reads answers 404 - never the names of that tenant's tasks"() {
        given:
        def one = tasks.tenant()
        def other = tasks.tenant()
        tasks.create(one.token, TaskApi.logCheck(one.model, "it@example.com", [name: "Geheime Prüfung"]))

        String byOtherAdmin = "/tenant/admin/file-stores/${one.store.id}"
        String byAdminUnderOther = "/admin/tenants/${other.tenant.id}/file-stores/${one.store.id}"

        expect:
        tasks.refusal(other.token, "DELETE", byOtherAdmin)[0] == 404
        tasks.refusal(api.adminToken, "DELETE", byAdminUnderOther)[0] == 404
        !api.error(other.token, "DELETE", byOtherAdmin).contains("Geheime Prüfung")
        !api.error(api.adminToken, "DELETE", byAdminUnderOther).contains("Geheime Prüfung")

        and: "the store is untouched - its own tenant is still refused while the task reads it"
        tasks.refusal(one.token, "DELETE", "/tenant/admin/file-stores/${one.store.id}") == [409, "fileStore.inUse"]
    }

    def "a tenant whose task reads a file store is deleted with both"() {
        given:
        def setup = tasks.tenant()
        tasks.create(setup.token, TaskApi.logCheck(setup.model, "it@example.com"))

        when:
        def status = api.status(api.adminToken, "DELETE", "/admin/tenants/${setup.tenant.id}")

        then:
        status.code == 204
        api.status(api.adminToken, "GET", "/admin/tenants/${setup.tenant.id}").code == 404
    }

    /** A task with one step written in place of the steps - its first line is 5. */
    private static String step(String body) {
        "version: 1\nname: A\nnotify: [a@example.com]\nsteps:\n  - " + body
    }

    /** read_documents "d", extract "x" (date, partner, kind), then write_file "w" whose remaining fields begin on line 21. */
    private static String writes(String rest) {
        extract("    fields:\n      - {name: date, type: date}\n      - {name: partner, type: text}\n" +
                "      - {name: kind, type: choice, values: [a, b]}\n  - id: w\n    type: write_file\n    input: x\n" +
                "    store: X\n" + rest)
    }

    /** read_documents "d", then an extract step "x" whose remaining fields begin on line 13. */
    private static String extract(String rest) {
        "version: 1\nname: A\nnotify: [a@example.com]\nsteps:\n  - id: d\n    type: read_documents\n    store: X\n" +
                "    pattern: '*'\n  - id: x\n    type: extract\n    model: M\n    input: d\n" + rest
    }

    /** A task with one trigger written in place of the trigger's fields - its first field is on line 6. */
    private static String triggers(String fields) {
        "version: 1\nname: A\nnotify: [a@example.com]\ntriggers:\n  - type: schedule\n    " + fields + "\nsteps: []"
    }

    def "a schedule is due after a save - from now, none while switched off; the list says it readably"() {
        given:
        def setup = tasks.tenant()
        String source = TaskApi.logCheck(setup.model, "it@example.com").replace("steps:\n",
                "triggers:\n  - type: schedule\n    every: 5h\n  - type: schedule\n    cron: \"0 30 6 * * MON-FRI\"\nsteps:\n")

        when:
        def created = tasks.create(setup.token, source)

        then:
        created.triggers == ["every 5h", "cron 0 30 6 * * MON-FRI"]
        Instant.parse(created.nextRunAt as String).isAfter(Instant.now())
        created.keys == []

        when: "switched off"
        def off = api.call(setup.token, "PUT", "${TASKS}/${created.id}", [source: source, active: false]) as Map

        then:
        off.nextRunAt == null
        (api.call(setup.token, "GET", TASKS) as List<Map>)[0].triggers == ["every 5h", "cron 0 30 6 * * MON-FRI"]
    }

    /** The log check with an edge after its verify step. */
    private static String loop(String condition, String rest) {
        """\
        version: 1
        name: A
        notify: [a@example.com]
        steps:
          - id: logs
            type: read_files
            store: X
            pattern: a.log
          - id: check
            type: agent
            model: M
            input: [logs]
            checks:
              - {name: A, severity: error, instruction: B}
          - id: verify
            type: verify
            of: check
            next:
              - if: ${condition}
                to: check${rest}
          - id: mail
            type: send_mail
            input: [verify]
        """.stripIndent()
    }
}
