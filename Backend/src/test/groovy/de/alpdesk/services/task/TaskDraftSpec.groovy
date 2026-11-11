package de.alpdesk.services.task

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.time.Duration
import java.time.Instant

import static de.alpdesk.services.task.TaskApi.TASKS

/**
 * "Draft with AI" (docs/architecture/tasks.md): the tenant's chat model writes a task from a description, told the format
 * and the tenant's names - never a file's content -, the check corrects it over at most three rounds, and nothing is
 * saved. FakeAiModels writes the task of "[[draft <name>]]" from the first store and model the prompt lists.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
class TaskDraftSpec extends Specification {

    static final String DRAFT = TASKS + "/draft"

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

    def setup() {
        api = api ?: new AiApi(client)
        tasks = tasks ?: new TaskApi(api)
        models.reset()
        store.reset()
    }

    def "a description becomes a checked draft with the tenant's names - nothing is saved, the usage is the asker's"() {
        given:
        def setup = tasks.tenant()

        when:
        def draft = api.call(setup.token, "POST", DRAFT, [description: "Fasse die Logs zusammen [[draft Nacht]]",
                                                         modelId    : setup.model.id]) as Map

        then:
        draft.valid
        draft.rounds == 1
        (draft.source as String).contains("name: Nacht")
        (draft.source as String).contains("store: Logdateien")
        (draft.steps as List<Map>)*.id == ["logs", "summary", "mail"]
        api.call(setup.token, "GET", TASKS) == []

        and: "the model was told the format and the tenant's names, nothing of its files"
        String system = models.chats.last().messages.first().text()
        system.contains("=== The format ===")
        system.contains("File stores: Logdateien (folder /logs")
        system.contains("Chat models: " + setup.model.displayName)

        and:
        (api.admin("GET", "/admin/usage?tenantId=${setup.tenant.id}") as List<Map>).sum { it.calls as int } == 1
    }

    def "the draft is told the newest file names of each store - never what is in them"() {
        given:
        def setup = tasks.tenant()
        (1..25).each { day ->
            store.write("/logs/app-2026-09-${String.format('%02d', day)}.log", "ERROR geheime Zeile ${day}\n")
            store.modified["/logs/app-2026-09-${String.format('%02d', day)}.log"] = Instant.parse("2026-09-01T00:00:00Z").plus(Duration.ofDays(day))
        }

        when:
        api.call(setup.token, "POST", DRAFT, [description: "[[draft Logs]]", modelId: setup.model.id])

        then:
        String system = models.chats.last().messages.first().text()
        system.contains("Logdateien (folder /logs; files: app-2026-09-25.log, app-2026-09-24.log,")
        system.contains("app-2026-09-06.log)")
        !system.contains("app-2026-09-05.log")
        !system.contains("geheime Zeile")
    }

    def "a store out of reach goes without its file names - the draft is written all the same"() {
        given:
        def setup = tasks.tenant()
        store.unreachable = true

        when:
        def draft = api.call(setup.token, "POST", DRAFT, [description: "[[draft Logs]]", modelId: setup.model.id]) as Map

        then:
        draft.valid
        models.chats.last().messages.first().text().contains("File stores: Logdateien (folder /logs)\n")
    }

    def "a store whose last check failed is not connected to - the draft names it without its files"() {
        given: "the real client's check of localhost:22 (nothing there, or another key) is recorded with the store"
        def setup = tasks.tenant()
        def check = api.call(setup.token, "POST", "/tenant/admin/file-stores/${setup.store.id}/check", [:]) as Map
        int opened = store.opened

        expect:
        check.status != "OK"
        (check.store as Map).checkError != null

        when:
        def draft = api.call(setup.token, "POST", DRAFT, [description: "[[draft Logs]]", modelId: setup.model.id]) as Map

        then:
        draft.valid
        store.opened == opened
        models.chats.last().messages.first().text().contains("File stores: Logdateien (folder /logs)\n")
    }

    def "a first draft the check refuses is corrected in the next round - at most three"() {
        given:
        def setup = tasks.tenant()

        when:
        def corrected = api.call(setup.token, "POST", DRAFT, [description: "[[draft A]] [[draft-wrong]]", modelId: setup.model.id]) as Map
        def never = api.call(setup.token, "POST", DRAFT, [description: "[[draft B]] [[draft-never]]", modelId: setup.model.id]) as Map

        then:
        corrected.valid
        corrected.rounds == 2
        models.chats.any { chat -> chat.messages.any { it.toString().contains("The check found these mistakes") } }
        !never.valid
        never.rounds == 3
        (never.errors as List<Map>)*.code == ["task.unknownField"]
    }

    def "a change of a task keeps its comments"() {
        given:
        def setup = tasks.tenant()
        String source = TaskApi.logCheck(setup.model, "it@example.com", [name: "Alt"])

        when:
        def draft = api.call(setup.token, "POST", DRAFT, [description: "Benenne sie um [[draft-change Neu]]", source: source,
                                                         modelId    : setup.model.id]) as Map

        then:
        draft.valid
        (draft.source as String).contains("name: Neu")
        (draft.source as String).contains("# who hears of a failed or unclear run")
    }

    def "without a chosen model the assistant's default model writes it - without one the draft is refused"() {
        given:
        def setup = tasks.tenant()

        expect: "no default model yet"
        tasks.refusal(setup.token, "POST", DRAFT, [description: "[[draft X]]"]) == [409, "assistant.noDefaultModel"]
        (api.call(setup.token, "GET", TASKS + "/draft-models") as Map).defaultModelId == null

        when:
        api.call(setup.token, "PUT", "/tenant/admin/assistant", [instructions: "Antworte knapp.", answerSource: "DOCUMENTS_FIRST",
                creativity: "HIGH", reasoning: "MEDIUM", searchResults: 8, defaultModelId: setup.model.id, imageModelId: null])

        then:
        (api.call(setup.token, "POST", DRAFT, [description: "[[draft X]]"]) as Map).valid
        (api.call(setup.token, "GET", TASKS + "/draft-models") as Map).defaultModelId == setup.model.id
    }

    def "a draft needs a description; another tenant's model is refused; the admin drafts too, the switch counts"() {
        given:
        def setup = tasks.tenant()
        def other = tasks.tenant()

        expect:
        tasks.refusal(setup.token, "POST", DRAFT, [description: " ", modelId: setup.model.id]) == [400, "task.draftDescriptionRequired"]
        tasks.refusal(setup.token, "POST", DRAFT, [description: "x" * 4001, modelId: setup.model.id]) == [400, "task.draftDescriptionTooLong"]
        tasks.refusal(setup.token, "POST", DRAFT, [description: "[[draft X]]", modelId: other.model.id])[0] == 400
        (api.admin("POST", "/admin/tenants/${setup.tenant.id}/tasks/draft", [description: "[[draft X]]", modelId: setup.model.id]) as Map).valid

        when:
        tasks.allowTasks(setup.tenant, false)

        then:
        tasks.refusal(setup.token, "POST", DRAFT, [description: "[[draft X]]", modelId: setup.model.id]) == [409, "task.notAllowed"]
    }

    def "the reference comes in the asked language and names the step prompt with its choices"() {
        given:
        def setup = tasks.tenant()

        expect:
        ((api.call(setup.token, "GET", TASKS + "/reference?language=de") as Map).markdown as String).startsWith("Eine Aufgabe")
        ((api.call(setup.token, "GET", TASKS + "/reference?language=xx") as Map).markdown as String).startsWith("A task")
        ((api.call(setup.token, "GET", TASKS + "/reference?language=en") as Map).markdown as String).contains("`choices`")
    }
}
