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

import java.time.LocalDate
import java.time.ZoneId

import static de.alpdesk.services.task.TaskApi.LOGS
import static de.alpdesk.services.task.TaskApi.TASKS

/**
 * The step prompt (docs/architecture/tasks.md): a chain of prompts - each answer the next one's input -, a choice picked
 * from fixed words that a condition reads, an edge back that shows the step why it is asked again, the answers in the
 * run view and the mail. FakeAiModels answers "Antwort von <step> (Runde n)" with the first line of each file it got.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
class TaskPromptSpec extends Specification {

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
        TaskApi.mailbox()
        models.reset()
        store.reset()
        store.write(LOGS + "/" + LocalDate.now(ZoneId.of("Europe/Berlin")) + "_app.log", "ERROR Timeout to interface X\n")
    }

    def "each prompt of a chain gets the answer before - the mail and the run view show them"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        def task = tasks.create(setup.token, chain(setup.model, mail, "", ""))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        def answers = run.answers as List<Map>
        answers*.stepId == ["summary", "review"]
        (answers[0].text as String).startsWith("Antwort von summary (Runde 1)")
        (answers[0].text as String).contains("ERROR Timeout to interface X")
        (answers[1].text as String).contains("summary.txt: Antwort von summary (Runde 1)")

        and: "the mail carries the answer it was given"
        def mails = TaskApi.mailsTo(mail)
        mails.size() == 1
        (mails[0].content as String).contains("Antwort von review (Runde 1)")
    }

    def "a choice decides where the run goes - an edge back shows the step why it is asked again"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        def task = tasks.create(setup.token, chain(setup.model, mail, "[[choose rework,approve]]",
                "    choices: [approve, rework]\n    next:\n      - if: review.choice == rework\n        to: summary\n        maxRounds: 2\n"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == "DONE"
        (run.answers as List<Map>).collect { [it.stepId, it.round, it.choice] } ==
                [["summary", 1, null], ["review", 1, "rework"], ["summary", 2, null], ["review", 2, "approve"]]
        ((run.answers as List<Map>)[2].text as String).contains("Zurueckgeschickt")
        TaskApi.mailsTo(mail).size() == 1
    }

    def "a mail only on one choice is skipped on the other"() {
        given:
        def setup = tasks.tenant()
        String mail = recipient()
        def task = tasks.create(setup.token, chain(setup.model, mail, "[[choose approve]]",
                "    choices: [approve, rework]\n", "if: review.choice == rework"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.steps as List<Map>).find { it.stepId == "mail" }.state == "SKIPPED"
        TaskApi.mailsTo(mail).isEmpty()
    }

    def "a model that picks no choice is asked once more - a second miss fails the run"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, chain(setup.model, recipient(), marker, "    choices: [approve, rework]\n"))

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == state
        (run.run as Map).errorCode == code

        where:
        marker                     || state    | code
        "[[choose-late approve]]"  || "DONE"   | null
        "[[choose-none]]"          || "FAILED" | "task.choiceInvalid"
    }

    def "an agent step checks a prompt's answer - its evidence may point into it"() {
        given:
        def setup = tasks.tenant()
        String source = """\
            version: 1
            name: Antwort pruefen ${UUID.randomUUID()}
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
              - id: check
                type: agent
                model: ${setup.model.displayName}
                input: [summary]
                checks:
                  - name: Interface
                    severity: error
                    instruction: Nennt die Zusammenfassung einen Timeout? [[find Timeout]]
            """.stripIndent()
        def task = tasks.create(setup.token, source)

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        def check = (((run.steps as List<Map>).find { it.stepId == "check" }.result as Map).checks as List<Map>)[0]
        check.status == "finding"
        (check.evidence as List<Map>)*.file == ["summary.txt"]
    }

    def "the check knows the step prompt: its fields, its choices and the conditions on them"() {
        given:
        def setup = tasks.tenant()

        expect:
        (tasks.validate(setup.token, chain(setup.model, "it@example.com", "", extra, mailIf)).errors as List<Map>)*.code
                .contains(code)

        where:
        extra                                                   | mailIf                           || code
        "    choices: [approve]\n"                              | ""                               || "task.choicesInvalid"
        "    choices: [approve, approve]\n"                     | ""                               || "task.choicesInvalid"
        "    choices: [Approve, rework]\n"                      | ""                               || "task.choicesInvalid"
        "    choices: [approve, rework]\n"                      | "if: review.choice == maybe"     || "task.conditionChoiceUnknown"
        ""                                                      | "if: review.choice == approve"   || "task.conditionChoiceUnknown"
        "    colour: blue\n"                                    | ""                               || "task.unknownField"
    }

    /** logs, summary (a prompt), review (a prompt on the summary with the extra lines), mail of the review. */
    private static String chain(Map model, String mail, String marker, String reviewExtra, String mailIf = "") {
        """\
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
            model: ${model.displayName}
            input: [logs]
            prompt: Fasse die Fehler der Nacht zusammen.
          - id: review
            type: prompt
            model: ${model.displayName}
            input: [summary]
            prompt: Ist das klar genug? ${marker}
        """.stripIndent() + reviewExtra + """\
          - id: mail
            type: send_mail
            input: [review]
            to: [${mail}]
        """.stripIndent().replaceAll(/(?m)^/, "  ") + (mailIf ? "    ${mailIf}\n" : "")
    }

    private static String recipient() {
        "it-" + UUID.randomUUID() + "@example.com"
    }
}
