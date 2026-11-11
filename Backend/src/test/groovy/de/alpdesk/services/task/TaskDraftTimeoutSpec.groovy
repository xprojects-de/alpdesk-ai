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

import static de.alpdesk.services.task.TaskApi.TASKS

/**
 * The draft's time limit (app.task.draft-timeout): once it is over no further round is asked, and the last version
 * comes back with the mistakes left - so the editor gets an answer before its own request timeout. A limit of zero
 * leaves the first round only.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
@Property(name = "app.task.draft-timeout", value = "0s")
class TaskDraftTimeoutSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels models

    @Shared
    AiApi api

    @Shared
    TaskApi tasks

    def setup() {
        api = api ?: new AiApi(client)
        tasks = tasks ?: new TaskApi(api)
        models.reset()
    }

    def "after the time limit no further round is asked - the last version comes back with its mistakes"() {
        given:
        def setup = tasks.tenant()

        when:
        def draft = api.call(setup.token, "POST", TASKS + "/draft", [description: "[[draft A]] [[draft-wrong]]",
                                                                    modelId    : setup.model.id]) as Map

        then:
        draft.rounds == 1
        !draft.valid
        (draft.errors as List<Map>)*.code == ["task.unknownField"]
        (draft.source as String).contains("name: A")
        models.chats.size() == 1
    }
}
