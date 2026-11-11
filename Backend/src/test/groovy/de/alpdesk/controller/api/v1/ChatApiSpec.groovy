package de.alpdesk.controller.api.v1

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * The client software asks the tenant's assistant with its API key: the same chat as the users', with conversations of
 * its own - seen by no user, and the users' not seen by it.
 */
@MicronautTest(transactional = false)
class ChatApiSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "the client software lists the models, asks, continues the conversation, reads and deletes it"() {
        given:
        def model = api.chatModel("GEMINI")
        def unusable = api.chatModel("ANTHROPIC")
        def notAssigned = api.chatModel("GEMINI")
        def tenant = api.tenant()
        api.allow(tenant.id, model, unusable)
        def userToken = api.tenantAdminToken(tenant.id)
        api.call(userToken, "PUT", "/tenant/admin/provider-keys/GEMINI", [apiKey: "own-gemini-key-0002"])
        def key = apiKey(tenant)

        expect:
        def models = (api.call(key, "GET", "/api/v1/models") as List<Map>).collectEntries { [(it.id): it] }
        models[model.id] == [id: model.id, provider: "GEMINI", displayName: model.displayName, usable: true]
        models[unusable.id].usable == false

        and: "a model not assigned to the tenant is neither listed nor answers"
        !models.containsKey(notAssigned.id)
        api.status(key, "POST", "/api/v1/chat", [modelId: notAssigned.id, message: "Hallo"]) == HttpStatus.BAD_REQUEST

        when:
        def first = api.call(key, "POST", "/api/v1/chat", [modelId: model.id, message: "Wann ist Inventur?"])
        def second = api.call(key, "POST", "/api/v1/chat", [conversationId: first.conversation.id, modelId: model.id,
                                                            message: "Und am Wochenende?"])

        then:
        first.answer.role == "ASSISTANT"
        first.answer.content == "Antwort auf: Wann ist Inventur? (Auszüge: 0)"
        second.conversation.id == first.conversation.id
        (fake.chats.last().messages[2] as AiMessage).text() == first.answer.content
        (fake.chats.last().messages[3] as UserMessage).singleText() == "Und am Wochenende?"
        (api.call(key, "GET", "/api/v1/chat/conversations") as List).collect { it.id } == [first.conversation.id]
        (api.call(key, "GET", "/api/v1/chat/conversations/${first.conversation.id}/messages") as List)
                .collect { it.role } == ["USER", "ASSISTANT", "USER", "ASSISTANT"]

        and: "another API key of the same tenant does not see them - they may quote areas only this key finds"
        api.call(apiKey(tenant), "GET", "/api/v1/chat/conversations") == []

        and: "the usage is the tenant's, without a user"
        (api.call(userToken, "GET", "/tenant/admin/usage") as List<Map>)[0].calls == 3 // two answers, one title

        when:
        def deleted = api.status(key, "DELETE", "/api/v1/chat/conversations/${first.conversation.id}")

        then:
        deleted == HttpStatus.NO_CONTENT
        api.call(key, "GET", "/api/v1/chat/conversations") == []
    }

    def "the client software's conversations and the users' never meet"() {
        given:
        def model = api.chatModel()
        def tenant = api.tenant()
        api.allow(tenant.id, model)
        def userToken = api.tenantAdminToken(tenant.id)
        api.call(userToken, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-anthropic-key-1"])
        def key = apiKey(tenant)
        def users = api.ask(userToken, [modelId: model.id, message: "Vom Benutzer"]).conversation
        def softwares = api.call(key, "POST", "/api/v1/chat", [modelId: model.id, message: "Von der Fachsoftware"]).conversation

        expect:
        (api.call(userToken, "GET", "/tenant/chat/conversations") as List).collect { it.id } == [users.id]
        (api.call(key, "GET", "/api/v1/chat/conversations") as List).collect { it.id } == [softwares.id]
        api.status(key, "GET", "/api/v1/chat/conversations/${users.id}/messages") == HttpStatus.NOT_FOUND
        api.status(key, "POST", "/api/v1/chat", [conversationId: users.id, modelId: model.id, message: "x"]) == HttpStatus.NOT_FOUND
        api.status(key, "DELETE", "/api/v1/chat/conversations/${users.id}") == HttpStatus.NOT_FOUND
        api.status(userToken, "GET", "/tenant/chat/conversations/${softwares.id}/messages") == HttpStatus.NOT_FOUND
    }

    def "another tenant's client software sees nothing"() {
        given:
        def model = api.chatModel()
        def owner = api.tenant()
        api.allow(owner.id, model)
        api.call(api.tenantAdminToken(owner.id), "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-anthropic-key-1"])
        def conversation = api.call(apiKey(owner), "POST", "/api/v1/chat", [modelId: model.id, message: "Hallo"]).conversation
        def otherKey = apiKey(api.tenant())

        expect:
        api.call(otherKey, "GET", "/api/v1/chat/conversations") == []
        api.status(otherKey, "GET", "/api/v1/chat/conversations/${conversation.id}/messages") == HttpStatus.NOT_FOUND
    }

    def "without a key for the provider the client software gets 409"() {
        given:
        def model = api.chatModel("OPENAI")
        def tenant = api.tenant()
        api.allow(tenant.id, model)
        def key = apiKey(tenant)

        expect:
        api.status(key, "POST", "/api/v1/chat", [modelId: model.id, message: "Hallo"]) == HttpStatus.CONFLICT
    }

    def "an API key without history: every question is answered on its own, nothing is stored but the usage"() {
        given:
        def setup = chatSetup()
        def key = apiKey(setup.tenant, false)

        when:
        def response = api.call(key, "POST", "/api/v1/chat", [modelId: setup.model.id, message: "Wann ist Inventur?"])

        then:
        response.conversation == null
        response.question == null
        response.answer.id == null
        response.answer.role == "ASSISTANT"
        response.answer.content == "Antwort auf: Wann ist Inventur? (Auszüge: 0)"
        response.answer.modelName == setup.model.displayName

        and: "no conversation, no title - one call in the usage"
        api.call(key, "GET", "/api/v1/chat/conversations") == []
        api.call(apiKey(setup.tenant), "GET", "/api/v1/chat/conversations") == []
        fake.titles.isEmpty()
        (api.call(setup.admin, "GET", "/tenant/admin/usage") as List<Map>)[0].calls == 1

        and: "it cannot continue a conversation, nor start one with files"
        api.status(key, "POST", "/api/v1/chat", [conversationId: 1, modelId: setup.model.id, message: "x"]) == HttpStatus.BAD_REQUEST
        api.chatUploadStatus(key, ["a.txt": "Text".getBytes()], null, "/api/v1/chat/files") == HttpStatus.BAD_REQUEST
        api.call(apiKey(setup.tenant), "GET", "/api/v1/chat/conversations") == []
    }

    def "the client software reads the answer as it comes - a key without history sends its files with the question"() {
        given:
        def setup = chatSetup()
        def key = apiKey(setup.tenant, false)
        def log = (1..100).collect { it == 50 ? "2026-10-07 06:00:50 ERROR spindle stopped" : "2026-10-07 06:00:00 INFO  cycle ${it}" }.join("\n")

        when:
        def events = api.events(key, "/api/v1/chat/stream", [modelId: setup.model.id,
                message: "Warum stand die Maschine? [[search error]] [[think Erst das Log lesen.]]",
                files: [[name: "machine.log", content: log]]])

        then: "the reasoning, each tool call, the answer's parts, then the whole answer as POST /api/v1/chat returns it"
        events*.event.first() == "thinking"
        events.findAll { it.event == "tool" }*.data*.name == ["search_file", "read_file_lines"]
        events.any { it.event == "delta" }
        events.last().event == "done"
        events.last().data.conversation == null
        events.last().data.answer.content.contains('1 matching line in "machine.log"')
        events.last().data.answer.modelName == setup.model.displayName

        and: "stored nowhere but the usage"
        api.call(key, "GET", "/api/v1/chat/conversations") == []
        (api.call(setup.admin, "GET", "/tenant/admin/usage") as List<Map>)[0].calls == 3
    }

    def "a key that keeps history streams into its conversation, stored like a user's"() {
        given:
        def setup = chatSetup()
        def key = apiKey(setup.tenant)

        when:
        def first = api.events(key, "/api/v1/chat/stream", [modelId: setup.model.id, message: "Wann ist Inventur?"]).last()
        def second = api.events(key, "/api/v1/chat/stream", [conversationId: first.data.conversation.id,
                modelId: setup.model.id, message: "Und danach?"]).last()

        then:
        first.event == "done"
        first.data.question.content == "Wann ist Inventur?"
        first.data.answer.content.startsWith("Antwort auf: Wann ist Inventur?")
        second.data.conversation.id == first.data.conversation.id
        (api.call(key, "GET", "/api/v1/chat/conversations/${first.data.conversation.id}/messages") as List)*.role ==
                ["USER", "ASSISTANT", "USER", "ASSISTANT"]
    }

    def "the stream is refused before it starts, as plain JSON, like POST /api/v1/chat: #reason"() {
        given:
        def setup = chatSetup()
        def key = apiKey(setup.tenant, keepsHistory)

        expect:
        api.status(key, "POST", "/api/v1/chat/stream", body(setup)) == status

        where:
        reason                                      | keepsHistory | body                                                                             || status
        "a conversation with a key without history" | false        | { s -> [conversationId: 1, modelId: s.model.id, message: "x"] }                  || HttpStatus.BAD_REQUEST
        "files with a key that keeps history"       | true         | { s -> [modelId: s.model.id, message: "x", files: [[name: "a.txt", content: "a"]]] } || HttpStatus.BAD_REQUEST
        "an empty question"                         | false        | { s -> [modelId: s.model.id, message: " "] }                                     || HttpStatus.BAD_REQUEST
        "another's conversation"                    | true         | { s -> [conversationId: 999999, modelId: s.model.id, message: "x"] }             || HttpStatus.NOT_FOUND
    }

    def "a failing provider ends the stream with an error event - nothing is stored"() {
        given:
        def setup = chatSetup()
        def key = apiKey(setup.tenant)
        fake.failures = 1

        when:
        def events = api.events(key, "/api/v1/chat/stream", [modelId: setup.model.id, message: "Hallo"])

        then:
        events.last().event == "error"
        events.last().data.code.startsWith("provider.")
        api.call(key, "GET", "/api/v1/chat/conversations") == []
    }

    def "without a model the tenant's default model answers a key without history - 409 without one"() {
        given:
        def setup = chatSetup()
        def key = apiKey(setup.tenant, false)

        expect:
        api.status(key, "POST", "/api/v1/chat", [message: "Hallo"]) == HttpStatus.CONFLICT

        when:
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", [instructions: "", answerSource: "DOCUMENTS_ONLY",
                creativity: "MEDIUM", reasoning: "LOW", searchResults: 5, defaultModelId: setup.model.id])

        then:
        api.call(key, "POST", "/api/v1/chat", [message: "Hallo"]).answer.modelName == setup.model.displayName

        when: "the admin takes the default model from the tenant"
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}/models", [modelIds: []])

        then: "refused before a provider is asked"
        api.status(key, "POST", "/api/v1/chat", [message: "Hallo"]) == HttpStatus.BAD_REQUEST
    }

    def "a tenant admin switches a key's history - its next question follows"() {
        given:
        def setup = chatSetup()
        def created = api.call(setup.admin, "POST", "/tenant/admin/api-keys", [name: "Fachsoftware"])

        expect:
        created.key.keepsHistory == true
        api.call(created.plainKey, "POST", "/api/v1/chat", [modelId: setup.model.id, message: "Eins"]).conversation != null

        when:
        def updated = api.call(setup.admin, "PUT", "/tenant/admin/api-keys/${created.key.id}", [keepsHistory: false])

        then:
        updated.keepsHistory == false
        updated.name == "Fachsoftware"
        api.call(created.plainKey, "POST", "/api/v1/chat", [modelId: setup.model.id, message: "Zwei"]).conversation == null
    }

    def "small files go with the question whole - a large one the model searches and reads itself"() {
        given:
        def setup = chatSetup()
        def key = apiKey(setup.tenant, false)
        def log = (1..100).collect { it == 50 ? "2026-10-07 06:00:50 ERROR spindle stopped"
                : "2026-10-07 06:00:${String.format('%02d', it % 60)} INFO  cycle ${it}" }.join("\n")

        when:
        def response = api.call(key, "POST", "/api/v1/chat", [modelId: setup.model.id,
                message: "Warum stand die Maschine? [[search error]]",
                files: [[name: "notiz.txt", content: "Schicht B"], [name: "machine.log", content: log]]])
        def first = fake.chats.first()
        def sent = (first.messages.find { it instanceof UserMessage } as UserMessage).singleText()

        then: "the note whole, the log as an overview, and the tools to read it"
        sent.contains('=== The file "notiz.txt" (sent with the question) ===\nSchicht B')
        sent.contains('=== The file "machine.log" (sent with the question, too large to go with it whole')
        sent.contains("characters, 100 lines")
        !sent.contains("cycle 7")
        first.tools.containsAll(["search_file", "read_file_lines"])
        (first.messages.first() as SystemMessage).text().contains("Explore them with\nsearch_file")

        and: "it searched, read the lines around the match and answered with them"
        fake.chats.size() == 3
        response.answer.content.contains('1 matching line in "machine.log":\n50: 2026-10-07 06:00:50 ERROR spindle stopped')
        response.answer.content.contains('Lines 49 to 51 of 100 in "machine.log":\n49: 2026-10-07 06:00:49 INFO  cycle 49')
        api.call(key, "GET", "/api/v1/chat/conversations") == []
    }

    def "exploring a large file may take up to 12 rounds - the last must answer"() {
        given:
        def setup = chatSetup()
        def key = apiKey(setup.tenant, false)

        when:
        def response = api.call(key, "POST", "/api/v1/chat", [modelId: setup.model.id, message: "Suche [[explore-forever]]",
                files: [[name: "machine.log", content: "ERROR x\n" * 400]]])

        then:
        fake.chats.size() == 12
        fake.chats[0..10].every { it.tools.contains("search_file") }
        fake.chats[11].tools.isEmpty()
        fake.chats[11].listedTools.contains("search_file")
        response.answer.content.startsWith("Antwort auf: Suche")
    }

    def "a model without tools searches the company's documents with the question alone - not the files sent with it"() {
        given:
        def model = api.chatModel("ANTHROPIC", [callsTools: false])
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        api.allow(tenant.id, model)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-anthropic-key-1"])
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        api.awaitDocument(admin, api.upload(admin, "betrieb.txt", "Die Anlage startet um 7 Uhr.").id)

        when:
        def response = api.call(apiKey(tenant, false), "POST", "/api/v1/chat", [modelId: model.id, message: "Prüfe dieses Log",
                files: [[name: "app.log", content: "2026-10-07 INFO Anlage gestartet"]]])

        then: "the search went by the question; the log came with it"
        fake.embeddings.last().texts == ["Prüfe dieses Log"]
        def question = (fake.chats.last().messages.last() as dev.langchain4j.data.message.UserMessage).contents()*.text.join("\n")
        question.startsWith("Prüfe dieses Log")
        question.contains("Anlage gestartet")
        response.answer.content
    }

    def "files that cannot come with a question are refused: #reason"() {
        given:
        def setup = chatSetup()
        def key = keepsHistory ? apiKey(setup.tenant) : apiKey(setup.tenant, false)

        def body = [modelId: setup.model.id, message: "Frage", files: files]

        expect:
        api.status(key, "POST", "/api/v1/chat", body) == HttpStatus.BAD_REQUEST
        api.error(key, "POST", "/api/v1/chat", body).startsWith(message)
        fake.chats.isEmpty()

        where:
        reason                       | keepsHistory | files                                                                    || message
        "too many"                   | false        | (1..21).collect { [name: "f${it}.txt".toString(), content: "x"] }        || "At most 20 files"
        "no name"                    | false        | [[name: " ", content: "x"]]                                              || "Every file needs a name"
        "a null entry"               | false        | [null]                                                                   || "Every file needs a name"
        "no content"                 | false        | [[name: "a.txt"]]                                                        || 'The file "a.txt" has no content'
        "too much text"              | false        | [[name: "a.txt", content: "x" * 150_000], [name: "b.txt", content: "y" * 50_001]] || "The files may have at most 200000 characters"
        "a key that keeps history"   | true         | [[name: "a.txt", content: "x"]]                                          || "Files come with a question only with an API key that keeps no history"
    }

    private Map chatSetup() {
        def model = api.chatModel()
        def tenant = api.tenant()
        api.allow(tenant.id, model)
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-anthropic-key-1"])
        [model: model, tenant: tenant, admin: admin]
    }

    private String apiKey(Map tenant, boolean keepsHistory = true) {
        api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Fachsoftware", keepsHistory: keepsHistory]).plainKey
    }
}
