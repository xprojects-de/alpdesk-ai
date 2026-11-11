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

/**
 * A mail server out of reach (docs/architecture/tasks.md "Mails"): a mail that cannot be sent never changes how a run
 * ends - the run is marked, and its findings stay in the run view. app.mail.* points at a port nobody listens on.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39526")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
class TaskMailFailureSpec extends Specification {

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
        store.write(LOGS + "/" + LocalDate.now(ZoneId.of("Europe/Berlin")) + "_app.log", "ERROR Timeout to interface X\n")
    }

    def "a mail that cannot be sent leaves the run's end as it is - the run is marked"() {
        given:
        def setup = tasks.tenant()
        def task = tasks.create(setup.token, TaskApi.logCheck(setup.model, "it-" + UUID.randomUUID() + "@example.com", options))
        store.unreachable = storeOutOfReach

        when:
        def run = tasks.awaitRun(setup.token, task.id, tasks.start(setup.token, task.id))

        then:
        (run.run as Map).state == state
        (run.run as Map).mailFailed == true

        where:
        mail                    | options                                                 | storeOutOfReach || state
        "findings (send_mail)"  | [:]                                                     | false           || "DONE"
        "findings unclear"      | [verifyRounds: 1, checkMarker: " [[unclear-until 9]]"]  | false           || "UNCLEAR"
        "run failed"            | [:]                                                     | true            || "FAILED"
    }
}
