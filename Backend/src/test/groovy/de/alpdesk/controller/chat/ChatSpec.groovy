package de.alpdesk.controller.chat

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import de.alpdesk.entities.ai.AiProvider
import de.alpdesk.entities.ai.KeySource
import de.alpdesk.entities.ai.ModelKind
import de.alpdesk.entities.ai.UsageRecord
import de.alpdesk.repositories.ai.UsageRecordRepository
import de.alpdesk.services.agent.AgentLoop
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.chat.ChatService
import de.alpdesk.services.common.ServiceException
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.StreamingHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import reactor.core.publisher.Flux
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The tenant users' chat: the chosen model answers with the conversation so far and the closest excerpts of the
 * tenant's documents; each conversation is its user's alone; every call is recorded as usage at the model's price.
 */
@MicronautTest(transactional = false)
class ChatSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    @Client("/")
    StreamingHttpClient streaming

    @Inject
    FakeAiModels fake

    @Inject
    ChatService chatService

    @Inject
    UsageRecordRepository usageRecords

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "a question starts a conversation; the next one continues it with the history"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)

        when:
        def first = api.ask(token, [modelId: model.id, message: "Wann ist Inventur?\nDanke"])

        then:
        first.conversation.title == "Thema: Wann ist Inventur?"
        first.question.role == "USER"
        first.question.content == "Wann ist Inventur?\nDanke"
        first.answer.role == "ASSISTANT"
        first.answer.content == "Antwort auf: Wann ist Inventur?\nDanke (Auszüge: 0)"
        first.answer.modelName == model.displayName
        first.sources == []

        when:
        def second = api.ask(token, [conversationId: first.conversation.id, modelId: model.id, message: "Und am Sonntag?"])

        then:
        second.conversation.id == first.conversation.id
        def sent = fake.chats.last().messages
        sent[0] instanceof SystemMessage
        (sent[1] as UserMessage).singleText() == "Wann ist Inventur?\nDanke"
        (sent[2] as AiMessage).text() == first.answer.content
        (sent[3] as UserMessage).singleText() == "Und am Sonntag?"

        and:
        (api.call(token, "GET", "/tenant/chat/conversations") as List).collect { it.id } == [first.conversation.id]
        (api.call(token, "GET", "/tenant/chat/conversations/${first.conversation.id}/messages") as List)
                .collect { it.role } == ["USER", "ASSISTANT", "USER", "ASSISTANT"]
    }

    def "the model searches the tenant's documents with its tool, the answer names what it found"() {
        given:
        def model = api.chatModel()
        def embedding = api.embeddingModel()
        def tenant = api.tenant(false, "GERMAN")
        def token = userWithKeys(tenant, model, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        def workshop = api.upload(token, "werkstatt.txt", "Die Werkstatt im Nebengebäude ist täglich von 15 bis 21 Uhr geöffnet.")
        def kitchen = api.upload(token, "kueche.txt", "Die Kantine bietet glutenfreies Brot auf Anfrage beim Mittagessen.")
        api.awaitDocument(token, workshop.id)
        api.awaitDocument(token, kitchen.id)

        when:
        def result = api.ask(token, [modelId: model.id, message: "Wann ist die Werkstatt im Nebengebäude geöffnet?"])

        then: "the search was offered and its rules given"
        fake.chats.first().tools.contains("similarity_search")
        (fake.chats.first().messages[0] as SystemMessage).text().contains("similarity_search first")
        (fake.chats.first().messages[0] as SystemMessage).text().contains(tenant.name)

        and: "it found the closest passage only"
        result.sources == ["werkstatt.txt"]
        result.answer.content.endsWith("(Auszüge: 1)")
        def found = fake.chats.last().messages.find { it instanceof dev.langchain4j.data.message.ToolExecutionResultMessage }
        found.text().contains("täglich von 15 bis 21 Uhr")
        !found.text().contains("glutenfreies Brot")
    }

    def "a streamed answer tells an nginx-based proxy not to hold it back - a plain answer does not"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)

        when:
        def streamed = client.toBlocking().exchange(HttpRequest.POST("/tenant/chat/stream", [modelId: model.id, message: "Hallo"])
                .accept(MediaType.TEXT_EVENT_STREAM_TYPE, MediaType.APPLICATION_JSON_TYPE).bearerAuth(token), String)
        def plain = client.toBlocking().exchange(HttpRequest.GET("/tenant/chat/conversations").bearerAuth(token), String)

        then:
        streamed.header("Content-Type").startsWith("text/event-stream")
        streamed.header("X-Accel-Buffering") == "no"
        streamed.header("X-Robots-Tag") == "noindex, nofollow"
        plain.header("X-Accel-Buffering") == null
    }

    def "the stream shows each tool before it runs - also a sign of life while tools take their time"() {
        given:
        def model = api.chatModel()
        def embedding = api.embeddingModel()
        def tenant = api.tenant()
        def token = userWithKeys(tenant, model, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])

        when:
        def events = api.askEvents(token, [modelId: model.id, message: "Wann ist die Werkstatt geöffnet?"])

        then:
        def tools = events.findAll { it.event == "tool" }*.data
        tools*.name == ["similarity_search"]
        tools*.software == [null]
        events.findIndexOf { it.event == "tool" } < events.findIndexOf { it.event == "done" }
    }

    def "a model that cannot call tools gets the search's results and the conversation's files with the question"() {
        given:
        def model = api.chatModel("ANTHROPIC", [callsTools: false])
        def embedding = api.embeddingModel()
        def tenant = api.tenant()
        def token = userWithKeys(tenant, model, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        def rules = api.upload(token, "wartung.txt", "Die Wartung der Anlage erfolgt jeden Montag um 7 Uhr.")
        api.awaitDocument(token, rules.id)
        def uploaded = api.chatUpload(token, ["notiz.txt": "Ersatzteile liegen im Lager B.".getBytes("UTF-8")])

        when:
        def events = api.askEvents(token, [conversationId: uploaded.conversation.id, modelId: model.id,
                                            message: "Wann ist die Wartung der Anlage?"])

        then: "no tool is offered - the application ran them before asking"
        fake.chats.size() == 1
        fake.chats.first().tools == []
        def system = (fake.chats.first().messages[0] as SystemMessage).text()
        system.contains("The application searched the company's documents")
        !system.contains("similarity_search")

        and: "the question comes with what the search found and the file's text"
        def question = (fake.chats.first().messages.last() as UserMessage).contents()*.text.join("\n")
        question.startsWith("Wann ist die Wartung der Anlage?")
        question.contains("jeden Montag um 7 Uhr")
        question.contains("Ersatzteile liegen im Lager B.")

        and: "the documents found are the answer's sources, sent before the answer"
        events.first().event == "sources"
        events.first().data.sources == ["wartung.txt"]
        events.last().data.sources == ["wartung.txt"]
    }

    def "a new conversation gets the title its model writes from the first question; later questions keep it"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)

        when:
        def first = api.ask(token, [modelId: model.id, message: "Wie plane ich die Woche?\nMit Details"])
        def second = api.ask(token, [conversationId: first.conversation.id, modelId: model.id, message: "Und am Sonntag?"])

        then:
        first.conversation.title == "Thema: Wie plane ich die Woche?"
        second.conversation.title == "Thema: Wie plane ich die Woche?"
        fake.titles == ["Wie plane ich die Woche?\nMit Details"]
        (api.call(token, "GET", "/tenant/chat/conversations") as List)*.title == ["Thema: Wie plane ich die Woche?"]
    }

    def "without a title from the model the conversation keeps the question's first line"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)
        fake.titleFails = true

        when:
        def answer = api.ask(token, [modelId: model.id, message: "Wann ist Inventur?\nDanke"])

        then: "the answer is not touched by it"
        answer.conversation.title == "Wann ist Inventur?"
        answer.answer.content.startsWith("Antwort auf:")
    }

    def "a conversation started with files gets its title at the first question"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)
        def uploaded = api.chatUpload(token, ["notiz.txt": "Die Anlage startet um 15 Uhr.".getBytes("UTF-8")])

        when:
        def answer = api.ask(token, [conversationId: uploaded.conversation.id, modelId: model.id, message: "Wann startet sie?"])

        then:
        uploaded.conversation.title == "Dateien: notiz.txt"
        answer.conversation.title == "Thema: Wann startet sie?"
    }

    def "a conversation is its user's alone"() {
        given:
        def model = api.chatModel()
        def tenant = api.tenant()
        def owner = userWithKeys(tenant, model)
        def colleague = api.tenantAdminToken(tenant.id)
        def conversation = api.ask(owner, [modelId: model.id, message: "Privat"]).conversation

        expect:
        api.call(colleague, "GET", "/tenant/chat/conversations") == []
        api.status(colleague, "GET", "/tenant/chat/conversations/${conversation.id}/messages") == HttpStatus.NOT_FOUND
        api.status(colleague, "POST", "/tenant/chat/stream", [conversationId: conversation.id, modelId: model.id, message: "x"]) == HttpStatus.NOT_FOUND
        api.status(colleague, "DELETE", "/tenant/chat/conversations/${conversation.id}") == HttpStatus.NOT_FOUND

        when:
        def deleted = api.status(owner, "DELETE", "/tenant/chat/conversations/${conversation.id}")

        then:
        deleted == HttpStatus.NO_CONTENT
        api.call(owner, "GET", "/tenant/chat/conversations") == []
    }

    def "invalid questions are refused: #reason"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)

        expect:
        api.status(token, "POST", "/tenant/chat/stream", [modelId: modelId ?: model.id, message: message]) == HttpStatus.BAD_REQUEST

        where:
        reason          | modelId    | message
        "empty"         | null       | "  "
        "too long"      | null       | "x" * 20_001
        "unknown model" | 999999999  | "Hallo"
    }

    def "a refusal before the stream is a JSON error for a client accepting the stream and JSON"() {
        given:
        def token = userWithKeys(api.tenant())

        when:
        client.toBlocking().exchange(HttpRequest.POST("/tenant/chat/stream", [modelId: 999999999, message: "Hallo"])
                .header(HttpHeaders.ACCEPT, "text/event-stream, application/json").bearerAuth(token), String)

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
        e.response.getBody(Map).get().message == "Unknown or unavailable chat model"
    }

    def "an embedding or image model cannot answer, an inactive model neither, a model not assigned to the tenant neither"() {
        given:
        def embedding = api.embeddingModel()
        def image = api.imageModel()
        def inactive = api.chatModel()
        def notAssigned = api.chatModel()
        def token = userWithKeys(api.tenant(), embedding, image, inactive)
        api.admin("PUT", "/admin/models/${inactive.id}", inactive + [active: false])

        expect:
        api.status(token, "POST", "/tenant/chat/stream", [modelId: embedding.id, message: "Hallo"]) == HttpStatus.BAD_REQUEST
        api.status(token, "POST", "/tenant/chat/stream", [modelId: image.id, message: "Hallo"]) == HttpStatus.BAD_REQUEST
        api.status(token, "POST", "/tenant/chat/stream", [modelId: inactive.id, message: "Hallo"]) == HttpStatus.BAD_REQUEST
        api.status(token, "POST", "/tenant/chat/stream", [modelId: notAssigned.id, message: "Hallo"]) == HttpStatus.BAD_REQUEST
    }

    def "the answer streams in pieces before the stored result"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)

        when:
        def events = api.askEvents(token, [modelId: model.id, message: "Wann ist Inventur?"])

        then: "the text piece by piece, then the stored exchange - no sources without a search"
        events.findAll { it.event == "sources" } == []
        def parts = events.findAll { it.event == "delta" }.collect { it.data.text }
        parts.size() > 3
        parts.join("") == "Antwort auf: Wann ist Inventur? (Auszüge: 0)"
        events.last().event == "done"
        events.last().data.answer.content == parts.join("")
        events.last().data.answer.id != null
    }

    def "a thinking model's reasoning streams before its answer - shown, never stored"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)

        when:
        def events = api.askEvents(token, [modelId: model.id, message: "Wann ist Inventur? [[think Erst die Zeiten prüfen.]]"])
        def thinking = events.findAll { it.event == "thinking" }.collect { it.data.text }.join("")

        then: "the reasoning first, apart from the answer"
        thinking == "Erst die Zeiten prüfen."
        events.findIndexOf { it.event == "thinking" } < events.findIndexOf { it.event == "delta" }
        def deltas = events.findAll { it.event == "delta" }.collect { it.data.text }.join("")
        events.last().data.answer.content == deltas

        and: "only the answer is stored"
        def stored = api.call(token, "GET", "/tenant/chat/conversations/${events.last().data.conversation.id}/messages") as List<Map>
        stored.find { it.role == "ASSISTANT" }.content == deltas
    }

    def "an answer without text is an error, not an answer - nothing is stored: #reason"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)

        when:
        def events = api.askEvents(token, [modelId: model.id, message: "Analysiere das Log " + marker])

        then:
        events.last().event == "error"
        events.last().data.message == model.displayName + message
        api.call(token, "GET", "/tenant/chat/conversations") == []

        where:
        reason                                 | marker               || message
        "the model gave none"                  | "[[empty]]"          || " gave no answer - please try again"
        "its reasoning used up the limit"      | "[[empty-length]]"   || " used up its output limit on its reasoning before it answered - an admin raises the model's output tokens in the model catalog"
    }

    def "an answer asked for again is stored like any other - the asking itself is not, nor sent with the next question"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)

        when:
        def first = api.ask(token, [modelId: model.id, message: "Analysiere das Log [[empty-once]]"])
        def messages = api.call(token, "GET", "/tenant/chat/conversations/${first.conversation.id}/messages") as List<Map>

        then:
        first.answer.content.startsWith("Antwort auf: Analysiere das Log")
        messages*.role == ["USER", "ASSISTANT"]
        messages.every { !it.content.contains(AgentLoop.ASK_AGAIN) }

        when:
        api.ask(token, [modelId: model.id, message: "Und weiter?", conversationId: first.conversation.id])

        then:
        fake.chats.last().messages.every { !(it instanceof UserMessage) || !it.hasSingleText() || it.singleText() != AgentLoop.ASK_AGAIN }
    }

    def "an answer without text over the API is a 502 too"() {
        given:
        def tenant = api.tenant()
        def model = api.chatModel()
        userWithKeys(tenant, model)
        def key = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Software"]).plainKey as String

        expect:
        api.status(key, "POST", "/api/v1/chat", [modelId: model.id, message: "Log [[empty-length]]"]) == HttpStatus.BAD_GATEWAY
        api.call(key, "GET", "/api/v1/chat/conversations") == []
    }

    def "a failing provider ends the stream with an error and leaves nothing behind"() {
        given:
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)
        fake.failures = 1

        when:
        def events = api.askEvents(token, [modelId: model.id, message: "Hallo"])

        then:
        events.collect { it.event } == ["error"]
        events.last().data.message == "ANTHROPIC refused the API key - check it under API-Keys"
        api.call(token, "GET", "/tenant/chat/conversations") == []
        and: "only the conversation's title was asked - recorded, it was a call (in the background: waited for)"
        new PollingConditions(timeout: 5).eventually {
            assert (api.call(token, "GET", "/tenant/admin/usage") as List<Map>)*.calls == [1]
            assert fake.titles == ["Hallo"]
        }
    }

    def "every answer is recorded at the model's price; with the admin's key it is billed"() {
        given:
        def model = api.chatModel("OPENAI")
        api.admin("PUT", "/admin/provider-keys/OPENAI", [apiKey: "sk-global-openai-5678"])
        def tenant = api.tenant(true)
        api.allow(tenant.id, model)
        def token = api.tenantAdminToken(tenant.id)

        when:
        2.times { api.ask(token, [modelId: model.id, message: "Hallo"]) }
        def usage = api.call(token, "GET", "/tenant/admin/usage") as List<Map>

        then: "1000 input tokens at 3, 200 output tokens at 15 per million - twice"
        usage.size() == 1
        usage[0].keySource == "GLOBAL"
        usage[0].calls == 4 // two answers, two titles of new conversations
        usage[0].inputTokens == 2000
        usage[0].outputTokens == 400
        usage[0].amount == 0.012

        and: "the admin sees it for the tenant, another tenant does not"
        (api.admin("GET", "/admin/usage?tenantId=${tenant.id}") as List<Map>).collect { [it.tenantName, it.amount] } == [[tenant.name, 0.012]]
        api.call(api.tenantAdminToken(api.tenant().id), "GET", "/tenant/admin/usage") == []

        and: "nothing in another month"
        api.admin("GET", "/admin/usage?tenantId=${tenant.id}&month=2020-01") == []
        api.status(api.adminToken, "GET", "/admin/usage?month=Oktober") == HttpStatus.BAD_REQUEST
    }

    def "the usage of a month runs from midnight to midnight in app.timezone, not in UTC"() {
        given: "two calls on the night of 31 January to 1 February in Berlin (UTC+1)"
        def tenant = api.tenant()
        usageAt(tenant, "2026-01-31T22:30:00Z", 1.0) // 23:30 on 31 January
        usageAt(tenant, "2026-01-31T23:30:00Z", 2.0) // 00:30 on 1 February

        expect:
        (api.admin("GET", "/admin/usage?tenantId=${tenant.id}&month=2026-01") as List<Map>)*.amount == [1.0]
        (api.admin("GET", "/admin/usage?tenantId=${tenant.id}&month=2026-02") as List<Map>)*.amount == [2.0]
        (api.call(api.tenantAdminToken(tenant.id), "GET", "/tenant/admin/usage?month=2026-02") as List<Map>)*.amount == [2.0]
    }

    private void usageAt(Map tenant, String time, BigDecimal amount) {
        usageRecords.save(new UsageRecord(null, tenant.id as Long, null, AiProvider.OPENAI, "chat-month", ModelKind.CHAT,
                KeySource.GLOBAL, 100, 10, 0, 0, false, amount, Instant.parse(time)))
    }

    /** A user of the tenant, which may use these models, with the tenant's own keys for every provider: its token. */
    def "a stream nobody listens to any more still stores the whole answer - stop only stops listening"() {
        given: "a long answer, streamed word by word on the provider's thread"
        def model = api.chatModel()
        def token = userWithKeys(api.tenant(), model)
        fake.streamsOnOwnThread = true
        def question = (1..2000).collect { "w$it" }.join(" ")

        when: "the client goes after the first piece"
        Flux.from(streaming.dataStream(HttpRequest.POST("/tenant/chat/stream", [modelId: model.id, message: question])
                .accept(MediaType.TEXT_EVENT_STREAM_TYPE).bearerAuth(token))).take(1).blockLast()

        then:
        new PollingConditions(timeout: 10).eventually {
            def conversations = api.call(token, "GET", "/tenant/chat/conversations") as List<Map>
            assert conversations.size() == 1
            def stored = api.call(token, "GET", "/tenant/chat/conversations/${conversations[0].id}/messages") as List<Map>
            assert stored*.role == ["USER", "ASSISTANT"]
            assert stored[1].content.contains("w2000")
        }
    }

    def "a conversation deleted while it is answered stores nothing and says it is gone"() {
        given: "a question prepared in a conversation, which the user deletes before the answer is stored"
        def model = api.chatModel()
        def tenant = api.tenant()
        userWithKeys(tenant, model)
        def user = api.user(tenant.id)
        def conversationId = api.ask(user.token as String, [modelId: model.id, message: "Hallo"]).conversation.id
        def prepared = chatService.prepare(tenant.id as Long, user.id as Long, conversationId as Long, model.id as Long,
                null, "Und weiter?", null)
        api.status(user.token as String, "DELETE", "/tenant/chat/conversations/${conversationId}")
        def ended = new CompletableFuture<Object>()

        when:
        chatService.stream(prepared, {}, {}, {}, {}, { ended.complete(it) }, { ended.complete(it) })

        then:
        def failure = ended.get(10, TimeUnit.SECONDS)
        failure instanceof ServiceException
        (failure as ServiceException).code() == "conversation.notFound"
        api.call(user.token as String, "GET", "/tenant/chat/conversations") == []
    }

    private String userWithKeys(Map tenant, Map... models) {
        api.allow(tenant.id, models)
        def token = api.tenantAdminToken(tenant.id)
        ["ANTHROPIC", "OPENAI", "GEMINI"].each { api.call(token, "PUT", "/tenant/admin/provider-keys/${it}", [apiKey: "own-key-of-" + it]) }
        token
    }
}
