package de.alpdesk.controller.chat

import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.services.a2a.A2aClient
import de.alpdesk.services.a2a.FakeA2aAgent
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.chat.ChatService
import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.oauth.FakeAuthorizationServer
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A tenant answering every question through its A2A agent (docs/architecture/a2a.md, roadmap point 54 package B): the
 * chat's stream fed by the agent's events, the conversation's context and the agent's question back, who asks in the
 * metadata, the files of the conversation sent once, the doors of the client software and the MCP server - and nothing
 * of our own model, templates or usage.
 */
@MicronautTest(transactional = false)
@Property(name = "app.a2a.timeout", value = "3s")
@Property(name = "app.a2a.max-answer-chars", value = "2000")
class AgentChatSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    ChatService chatService

    @Inject
    FakeAiModels fake

    @Shared
    AiApi api

    FakeA2aAgent agent = new FakeA2aAgent()

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
        agent.slowMillis = 5_000
    }

    def cleanup() {
        agent.stop()
    }

    def "every question goes to the agent: its pieces streamed, its working status a tool step, stored under its name"() {
        given:
        def setup = answering()

        when:
        def events = api.askEvents(setup.token, [message: "Wo liegt das Lager? [[task]]"])

        then:
        events*.event.contains("tool")
        events.find { it.event == "tool" }.data == [name: "Ich suche", software: "Fakeagent"]
        events.findAll { it.event == "delta" }*.data*.text.join("") == "Antwort von Fakeagent: Wo liegt das Lager?"
        def done = events.last()
        done.event == "done"
        done.data.answer.content == "Antwort von Fakeagent: Wo liegt das Lager?"
        done.data.answer.modelName == "Fakeagent"
        done.data.conversation.title == "Wo liegt das Lager? [[task]]"
        fake.chats.isEmpty()

        and: "the agent got the question with our extension, the key and A2A 1.0"
        def sent = agent.messages().last()
        sent.text == "Wo liegt das Lager? [[task]]"
        sent.authorization == "Bearer agent-key-123456"
        sent.version == "1.0"
        sent.extensions == A2aClient.ASKER_EXTENSION

        and: "nothing is recorded in the usage"
        (api.admin("GET", "/admin/usage") as Map).toString().contains(setup.tenant.name as String) == false
    }

    def "the conversation keeps the agent's context and sends it with the next question"() {
        given:
        def setup = answering()

        when:
        def first = api.ask(setup.token, [message: "Erste Frage"])
        api.ask(setup.token, [conversationId: first.conversation.id, message: "Zweite Frage"])

        then:
        def messages = agent.messages()
        messages[0].contextId == null
        messages[1].contextId != null
    }

    def "a question back is shown waiting, and the next question answers it - the agent's task goes on"() {
        given:
        def setup = answering()

        when:
        def asked = api.ask(setup.token, [message: "Bitte buchen [[ask-back Welches Konto?]]"])

        then:
        asked.answer.content == "Welches Konto?"
        asked.answer.waiting == true

        when:
        def answered = api.ask(setup.token, [conversationId: asked.conversation.id, message: "Konto 4711"])

        then:
        answered.answer.content == "Fortgesetzt: Konto 4711"
        answered.answer.waiting == false
        agent.messages().last().taskId != null
        (api.call(setup.token, "GET", "/tenant/chat/conversations/${asked.conversation.id}/messages") as List<Map>)*.waiting ==
                [false, true, false, false]
    }

    def "an answer to a question back that fails leaves the conversation usable - the next question starts a new task"() {
        given:
        def setup = answering()
        def asked = api.ask(setup.token, [message: "Bitte buchen [[ask-back Welches Konto?]]"])

        when: "the agent fails the task the answer went to"
        def failed = api.askEvents(setup.token, [conversationId: asked.conversation.id, message: "Konto 4711 [[failed-task]]"])

        then:
        failed.last().data.code == "a2a.agentFailed"
        agent.messages().last().taskId != null

        when: "the next question no longer names the ended task - the agent would refuse it"
        def next = api.ask(setup.token, [conversationId: asked.conversation.id, message: "Neue Frage"])

        then:
        next.answer.content == "Antwort von Fakeagent: Neue Frage"
        agent.messages().last().taskId == null
        agent.messages().last().contextId == agent.messages()[1].contextId
    }

    def "an answer to a question back the agent never got leaves its task waiting - the next try answers it"() {
        given:
        def setup = answering()
        def asked = api.ask(setup.token, [message: "Bitte buchen [[ask-back Welches Konto?]]"])

        when: "the agent refuses the request before it reads it"
        agent.forbidden = true
        def failed = api.askEvents(setup.token, [conversationId: asked.conversation.id, message: "Konto 4711"])

        then:
        failed.last().data.code == "a2a.agentFailed"

        when:
        agent.forbidden = false
        def answered = api.ask(setup.token, [conversationId: asked.conversation.id, message: "Konto 4711"])

        then:
        answered.answer.content == "Fortgesetzt: Konto 4711"
    }

    def "a stopped answer of the agent (the chat's stream closed) cancels its task and stores nothing"() {
        given: "a question whose stream the controller stops when it closes"
        def setup = answering()
        def prepared = chatService.prepare(setup.tenant.id as Long, setup.user.id as Long, null, null, null,
                "Lange Frage [[working]]", null)
        def ended = new CompletableFuture<Object>()

        when: "it is stopped once the agent's working status came - its task is known by then"
        chatService.stream(prepared, {}, {}, {}, { prepared.stop() }, { ended.complete(it) }, { ended.complete(it) })

        then:
        def failure = ended.get(10, TimeUnit.SECONDS)
        failure instanceof ServiceException
        (failure as ServiceException).code() == "answer.stopped"
        new PollingConditions(timeout: 5).eventually {
            assert agent.received*.method.contains("CancelTask")
        }
        (api.call(setup.token, "GET", "/tenant/chat/conversations") as List).isEmpty()
    }

    def "a stop that came before the agent was asked stops its answer - nothing is stored"() {
        given: "a question stopped while it was prepared (the client went away at once)"
        def setup = answering()
        def prepared = chatService.prepare(setup.tenant.id as Long, setup.user.id as Long, null, null, null,
                "Lange Frage [[working]]", null)
        def ended = new CompletableFuture<Object>()
        prepared.stop()

        when:
        chatService.stream(prepared, {}, {}, {}, {}, { ended.complete(it) }, { ended.complete(it) })

        then:
        def failure = ended.get(10, TimeUnit.SECONDS)
        failure instanceof ServiceException
        (failure as ServiceException).code() == "answer.stopped"
        (api.call(setup.token, "GET", "/tenant/chat/conversations") as List).isEmpty()
    }

    def "an agent whose card streams nothing answers in one piece - within the agent's time, not our model's"() {
        given:
        def setup = answering()
        agent.streams = false

        when:
        def events = api.askEvents(setup.token, [message: "Wo liegt das Lager?"])

        then:
        events.last().event == "done"
        events.last().data.answer.content == "Antwort von Fakeagent: Wo liegt das Lager?"
        agent.messages().last().method == "SendMessage"

        expect: "an answer slower than app.a2a.timeout ends as the agent's timeout"
        api.askEvents(setup.token, [message: "Langsam [[slow]]"]).last().data.code == "a2a.timeout"
    }

    def "a stop while an agent that streams nothing still answers stores nothing"() {
        given:
        def setup = answering()
        agent.streams = false
        agent.slowMillis = 800
        def prepared = chatService.prepare(setup.tenant.id as Long, setup.user.id as Long, null, null, null,
                "Lange Frage [[slow]]", null)
        def ended = new CompletableFuture<Object>()

        when: "it is stopped while the one request for the whole answer is open"
        chatService.stream(prepared, {}, {}, {}, {}, { ended.complete(it) }, { ended.complete(it) })
        Thread.sleep(250)
        prepared.stop()

        then:
        def failure = ended.get(10, TimeUnit.SECONDS)
        failure instanceof ServiceException
        (failure as ServiceException).code() == "answer.stopped"
        (api.call(setup.token, "GET", "/tenant/chat/conversations") as List).isEmpty()
    }

    def "an answer of a file alone is stored without text of its own; an answer of nothing is none"() {
        given:
        def setup = answering()

        when:
        def answer = api.ask(setup.token, [message: "Bericht [[file-only bericht.txt]]"]).answer

        then:
        answer.content == ""
        (answer.files as List<Map>)*.fileName == ["bericht.txt"]

        and:
        def empty = api.askEvents(setup.token, [message: "Nichts [[empty]]"]).last()
        empty.event == "error"
        empty.data.code == "chat.noAnswer"
    }

    def "who asks goes in the metadata: tenant, language, user, groups, IDM groups and the skills"() {
        given:
        def setup = answering()
        def group = api.group(setup.admin, [setup.user.id])

        when:
        def answer = api.ask(setup.token, [message: "Wer bin ich? [[echo-metadata]]"]).answer

        then:
        def metadata = io.micronaut.serde.ObjectMapper.getDefault().readValue(answer.content as String, Map)
        metadata.tenant == setup.tenant.name
        metadata.user == setup.user.username
        metadata.groups.toSet() == [group.name, "Standard"].toSet()
        metadata.idmGroups == []
        metadata.skills == ["rechnungen", "urlaub"]
        metadata.language != null
        agent.messages().last().messageExtensions == [A2aClient.ASKER_EXTENSION]
    }

    def "the agent's skills are the choice: listed, left out per question, kept with the conversation, unknown refused"() {
        given:
        def setup = answering()
        def keeping = api.admin("POST", "/admin/tenants/${setup.tenant.id}/api-keys", [name: "Fachsoftware"]).plainKey as String

        expect: "the skills instead of the connected software"
        api.call(setup.token, "GET", "/tenant/chat/software") == [
                [id: "skill:rechnungen", name: "Rechnungen", instructions: "Prüft Rechnungen"],
                [id: "skill:urlaub", name: "Urlaub", instructions: "Plant Urlaube"]]
        (api.call(keeping, "GET", "/api/v1/software") as List<Map>)*.id == ["skill:rechnungen", "skill:urlaub"]

        when: "a skill left out: the metadata names the skills left on, the conversation keeps the choice"
        def first = api.ask(setup.token, [message: "Frage [[echo-metadata]]", excludedSkillIds: ["urlaub"]])

        then:
        parse(first.answer.content).skills == ["rechnungen"]
        first.conversation.excludedSkillIds == ["urlaub"]

        when: "left out again: the stored choice"
        def second = api.ask(setup.token, [conversationId: first.conversation.id, message: "Weiter [[echo-metadata]]"])

        then:
        parse(second.answer.content).skills == ["rechnungen"]

        when: "empty: every skill on again"
        def third = api.ask(setup.token, [conversationId: first.conversation.id, message: "Alle [[echo-metadata]]",
                                          excludedSkillIds: []])

        then:
        parse(third.answer.content).skills == ["rechnungen", "urlaub"]
        third.conversation.excludedSkillIds == []

        and:
        api.error(setup.token, "POST", "/tenant/chat/stream", [message: "Frage", excludedSkillIds: ["lager"]]) ==
                "Unknown skill of the agent"
        api.status(keeping, "POST", "/api/v1/chat", [message: "Frage", excludedSkillIds: ["lager"]]) == HttpStatus.BAD_REQUEST
    }

    def "a user in the tenant's Standard alone is answered - Standard its one group in the metadata"() {
        given:
        def setup = answering()

        when:
        def answer = api.ask(setup.token, [message: "Hallo [[echo-metadata]]"]).answer

        then:
        io.micronaut.serde.ObjectMapper.getDefault().readValue(answer.content as String, Map).groups == ["Standard"]
    }

    def "the files uploaded since the last question go with it once; a video never"() {
        given:
        def setup = answering()
        def uploaded = api.chatUpload(setup.token, ["notiz.txt": "Ein Text".getBytes(StandardCharsets.UTF_8)])

        when:
        def first = api.ask(setup.token, [conversationId: uploaded.conversation.id, message: "Lies [[files]]"])
        def second = api.ask(setup.token, [conversationId: uploaded.conversation.id, message: "Nochmal [[files]]"])

        then:
        first.answer.content == "Dateien: notiz.txt | Lies"
        second.answer.content == "Dateien:  | Nochmal"
    }

    def "a file the agent returns is a file of the answer"() {
        given:
        def setup = answering()

        when:
        def answer = api.ask(setup.token, [message: "Bericht [[file bericht.txt]]"]).answer

        then:
        (answer.files as List<Map>)*.fileName == ["bericht.txt"]
    }

    def "no template with an agent; the agent's failure, slowness and too long an answer"() {
        given:
        def setup = answering()

        expect:
        api.error(setup.token, "POST", "/tenant/chat/stream", [message: "Frage", templateId: 1]) ==
                "The chat answers through an agent - its skills take the place of templates"
        api.askEvents(setup.token, [message: "Kaputt [[fail]]"]).last().data.code == "a2a.agentFailed"
        api.askEvents(setup.token, [message: "Langsam [[slow]]"]).last().data.code == "a2a.timeout"
        api.askEvents(setup.token, [message: "Buchen [[failed-task]]"]).last().data.message ==
                "Fakeagent ended its task failed: Die Buchung schlug fehl"
        (api.ask(setup.token, [message: "Lang [[long]]"]).answer.content as String).length() == 2003
    }

    def "the key's A2A card lists no template while the agent answers - it would be refused"() {
        given:
        def setup = answering()
        api.call(setup.admin, "POST", "/tenant/admin/assistant/templates", [name: "Kurzfassung", instructions: "Antworte kurz"])
        def key = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Plattform", a2a: true]) as Map).plainKey as String

        expect:
        (a2aRpc(key, "GetExtendedAgentCard", [:]).result.skills as List<Map>)*.id == ["answer"]
    }

    def "only an A2A agent answers the chat, and only one"() {
        given:
        def setup = answering()

        expect:
        api.status(setup.admin, "POST", "/tenant/admin/mcp-connections", [name: "Zweiter", baseUrl: agent.url(),
                                                                          protocol: "A2A", answersChat: true]) == HttpStatus.CONFLICT
        api.status(setup.admin, "POST", "/tenant/admin/mcp-connections", [name: "Software", baseUrl: agent.url(),
                                                                          answersChat: true]) == HttpStatus.BAD_REQUEST
    }

    def "the client software and the MCP server are answered by the agent too, with their key in the metadata"() {
        given:
        def setup = answering()
        def keeping = api.admin("POST", "/admin/tenants/${setup.tenant.id}/api-keys", [name: "Fachsoftware"]).plainKey as String
        def forgetting = api.admin("POST", "/admin/tenants/${setup.tenant.id}/api-keys",
                [name: "Ohne Verlauf", keepsHistory: false]).plainKey as String
        def mcpKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map).plainKey as String

        when:
        def kept = api.call(keeping, "POST", "/api/v1/chat", [message: "Frage [[echo-metadata]]"]) as Map
        def once = api.call(forgetting, "POST", "/api/v1/chat", [message: "Frage [[echo-metadata]]"]) as Map
        def streamed = api.events(keeping, "/api/v1/chat/stream", [message: "Gestreamt"])
        def viaMcp = mcp(mcpKey, "Frage [[echo-metadata]]")

        then:
        parse(kept.answer.content).apiKey == "Fachsoftware"
        parse(kept.answer.content).language == "simple"
        kept.conversation.id != null
        parse(once.answer.content).apiKey == "Ohne Verlauf"
        streamed.last().data.answer.content == "Antwort von Fakeagent: Gestreamt"
        viaMcp.contains('"mcpKey":"Claude"')
        (api.call(keeping, "GET", "/api/v1/me") as Map).answersThroughAgent == true
        (api.call(setup.token, "GET", "/tenant/account") as Map).answersThroughAgent == true
    }

    def "an answer of nothing is none for the client software, the MCP server and an A2A client too"() {
        given:
        def setup = answering()
        def forgetting = api.admin("POST", "/admin/tenants/${setup.tenant.id}/api-keys",
                [name: "Ohne Verlauf", keepsHistory: false]).plainKey as String
        def mcpKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Plattform", a2a: true]) as Map).plainKey as String
        def body = [jsonrpc: "2.0", id: 1, method: "SendMessage", params: [message: [role: "ROLE_USER",
                messageId: UUID.randomUUID().toString(), parts: [[text: "Nichts [[empty]]"]]]]]

        expect: "without history, in one piece and streamed"
        api.error(forgetting, "POST", "/api/v1/chat", [message: "Nichts [[empty]]"]) == "Fakeagent gave no answer - please try again"
        def streamed = api.events(forgetting, "/api/v1/chat/stream", [message: "Nichts [[empty]]"]).last()
        streamed.event == "error"
        streamed.data.code == "chat.noAnswer"

        and: "ask_assistant"
        mcp(mcpKey, "Nichts [[empty]]").contains("gave no answer")

        and: "/a2a, plain and streamed"
        def plain = client.toBlocking().retrieve(HttpRequest.POST("/a2a", body).header("A2A-Version", "1.0")
                .bearerAuth(mcpKey), Map)
        ((plain.error.data as List<Map>)[0].metadata as Map).code == "chat.noAnswer"
        a2aEvents(mcpKey, "Nichts [[empty]]").last().data.result.statusUpdate.status.state == "TASK_STATE_FAILED"
    }

    def "the agent's failure reaches an A2A client as data - the error's message and the log name its code only"() {
        given:
        def setup = answering()
        def key = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Plattform", a2a: true]) as Map).plainKey as String
        def body = [jsonrpc: "2.0", id: 1, method: "SendMessage", params: [message: [role: "ROLE_USER",
                messageId: UUID.randomUUID().toString(), parts: [[text: "Kaputt [[fail]]"]]]]]
        def since = java.time.Instant.now()

        when:
        def answer = client.toBlocking().retrieve(HttpRequest.POST("/a2a", body).header("A2A-Version", "1.0")
                .bearerAuth(key), Map)

        then:
        answer.error.message == "The answer failed (a2a.agentFailed)"
        def data = (answer.error.data as List<Map>)[0].metadata as Map
        data.code == "a2a.agentFailed"
        (data.error as String).contains("Der Agent ist gestört")
        and: "our server's line - the fake agent, in this JVM, logs its own failure with its own words"
        def lines = RecentLogAppender.installed().entries().findAll { !it.timestamp().isBefore(since) }*.message()
        lines.any { it.contains("InternalError - The answer failed (a2a.agentFailed)") }
        !lines.any { it.contains("Fakeagent could not answer") }
    }

    def "the MCP server offers no tools of ours when the agent answers - code and software are the agent's matter"() {
        given:
        def setup = answering()
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, a2aAllowed: true,
                sandboxUrls: "http://alpdesk-ai-sandbox-" + AiApi.unique() + ":8080", sandboxToken: "t" * 40])
        def mcpKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map).plainKey as String
        def message = [jsonrpc: "2.0", id: UUID.randomUUID().toString(), method: "tools/list", params: [:]]

        when:
        def body = client.toBlocking().retrieve(HttpRequest.POST("/mcp", message).bearerAuth(mcpKey), Map)

        then:
        !((body.result.tools as List<Map>).find { it.name == "ask_assistant" }.description as String).contains("It can also")
    }

    def "a streamed question at /a2a hands on the agent's files, as a plain one does"() {
        given:
        def setup = answering()
        def key = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Plattform", a2a: true]) as Map).plainKey as String

        when:
        def events = a2aEvents(key, "Bericht bitte [[file bericht.txt]]")

        then:
        def artifacts = events.collect { (it.data as Map)?.result?.artifactUpdate?.artifact }.findAll()
        artifacts*.parts.flatten().any { (it as Map).filename == "bericht.txt" }
        events.last().data.result.statusUpdate.status.state == "TASK_STATE_COMPLETED"
    }

    def "cancelling a streamed /a2a question stops the agent's task too"() {
        given:
        def setup = answering()
        def key = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Plattform", a2a: true]) as Map).plainKey as String
        def stream = Thread.start { a2aEvents(key, "Lange Frage [[running]]") }

        when:
        String taskId = null
        long until = System.currentTimeMillis() + 10_000
        while (taskId == null && System.currentTimeMillis() < until) {
            Thread.sleep(200)
            taskId = (a2aRpc(key, "ListTasks", [pageSize: 1]).result.tasks as List<Map>)?.find()?.id
        }
        a2aRpc(key, "CancelTask", [id: taskId])

        then:
        taskId != null
        new spock.util.concurrent.PollingConditions(timeout: 5).eventually {
            assert agent.received.any { it.method == "CancelTask" }
        }

        cleanup:
        stream?.join(10_000)
    }

    def "deleting a conversation that waits cancels the agent's task"() {
        given:
        def setup = answering()
        def asked = api.ask(setup.token, [message: "Buchen [[ask-back Welches Konto?]]"])

        when:
        api.status(setup.token, "DELETE", "/tenant/chat/conversations/${asked.conversation.id}")

        then:
        agent.received.any { it.method == "CancelTask" }
    }

    def "deleting a waiting conversation after the admin took A2A away tells the agent nothing"() {
        given:
        def setup = answering()
        def asked = api.ask(setup.token, [message: "Buchen [[ask-back Welches Konto?]]"])
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, a2aAllowed: false])

        when:
        api.status(setup.token, "DELETE", "/tenant/chat/conversations/${asked.conversation.id}")

        then:
        !agent.received.any { it.method == "CancelTask" }
    }

    def "a working status of any length reaches the chat as a line - cut at 500 characters"() {
        given:
        def setup = answering()

        when:
        def events = api.askEvents(setup.token, [message: "Wo liegt das Lager? [[task]] [[chatty]]"])

        then:
        events.find { it.event == "tool" }.data.name == "x" * 500 + " [cut off]"
    }

    def "without the admin's switch the agent does not answer; the check button reads the card"() {
        given:
        def setup = answering()

        when:
        def check = api.call(setup.admin, "POST", "/tenant/admin/mcp-connections/${setup.connection.id}/check") as Map

        then:
        check.ok == true
        check.name == "Fakeagent"
        check.skills == 2

        when:
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, a2aAllowed: false])

        then:
        api.error(setup.token, "POST", "/tenant/chat/stream", [message: "Frage"]) ==
                "The company's chat answers through an A2A agent the admin no longer allows"
        (api.call(setup.admin, "POST", "/tenant/admin/mcp-connections/${setup.connection.id}/check") as Map).ok == false
    }

    def "another tenant's chat is untouched - its own model answers"() {
        given:
        answering()
        def other = api.tenant()
        def chat = api.chatModel()
        api.allow(other.id, chat)
        def admin = api.tenantAdminToken(other.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])

        when:
        def answer = api.ask(api.userToken(other.id), [modelId: chat.id, message: "Hallo"]).answer

        then:
        answer.modelName == chat.displayName
        (api.call(admin, "GET", "/tenant/account") as Map).answersThroughAgent == false
    }

    def "a tenant answering through its agent asked at its MCP server while a call is open is answered (the loop warned)"() {
        given:
        def setup = answering()
        def mcpKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map).plainKey as String
        agent.slowMillis = 1_500
        def slow = Thread.start { api.askEvents(setup.token, [message: "Langsam [[slow]]"]) }
        Thread.sleep(300)

        when:
        def answer = mcp(mcpKey, "Parallel")

        then:
        answer.contains("Antwort von Fakeagent: Parallel")

        cleanup:
        slow?.join()
    }

    def "an agent signed in with OAuth: a token refused on the stream is renewed and the question sent once more; refused again, the sign-in is lost"() {
        given:
        def authServer = new FakeAuthorizationServer()
        agent.oauth = authServer
        def tenant = api.tenant()
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, a2aAllowed: true])
        def admin = api.tenantAdminToken(tenant.id)
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Fakeagent", baseUrl: agent.url(),
                                                                                    protocol: "A2A", answersChat: true]) as Map
        assert signIn(admin, connection.id).endsWith("signIn=ok")
        def user = api.user(tenant.id)
        api.ask(user.token as String, [message: "Erste Frage"])

        when: "the token is revoked at the server before it expires"
        authServer.revoke(agent.messages().last().authorization.substring(7))
        def again = api.ask(user.token as String, [message: "Zweite Frage"])

        then:
        again.answer.content == "Antwort von Fakeagent: Zweite Frage"
        authServer.refreshes.get() == 1
        agent.messages()*.text == ["Erste Frage", "Zweite Frage"]

        when: "the agent takes no token of this sign-in any more - renewed once, refused again"
        agent.key = "a-key-nobody-has"
        def lost = api.askEvents(user.token as String, [message: "Dritte Frage"]).last()

        then:
        lost.data.code == "a2a.agentFailed"
        (lost.data.message as String).contains("Fakeagent: needs a sign-in")
        authServer.refreshes.get() == 2
        ((api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>)[0].signIn as Map).status == "SIGN_IN_NEEDED"

        cleanup:
        authServer.stop()
    }

    /** Signs in as the tenant admin's browser: the server's page, our callback, the page completing it - its outcome. */
    private String signIn(String admin, Object connectionId) {
        def started = api.call(admin, "POST", "/tenant/admin/mcp-connections/${connectionId}/sign-in",
                [returnUrl: "http://127.0.0.1:4300/app/software"]) as Map
        def browser = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
        def atServer = browser.send(java.net.http.HttpRequest.newBuilder(URI.create(started.authorizationUrl as String)).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding())
        def callback = browser.send(java.net.http.HttpRequest.newBuilder(URI.create(atServer.headers().firstValue("Location").get())).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding())
        api.completeSignIn(admin, "/tenant/admin/mcp-connections", callback.headers().firstValue("Location").get())
    }

    private Map answering() {
        def tenant = api.tenant()
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, a2aAllowed: true])
        def admin = api.tenantAdminToken(tenant.id)
        agent.key = "agent-key-123456"
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Fakeagent", baseUrl: agent.url(),
                                                                                    protocol: "A2A", apiKey: agent.key, answersChat: true]) as Map
        assert connection.answersChat == true
        def user = api.user(tenant.id)
        [tenant: tenant, admin: admin, connection: connection, user: user, token: user.token]
    }

    /** A streamed question at /a2a: the data of each server-sent event. */
    private List<Map> a2aEvents(String key, String question) {
        def body = [jsonrpc: "2.0", id: 1, method: "SendStreamingMessage", params: [message: [role: "ROLE_USER",
                messageId: UUID.randomUUID().toString(), parts: [[text: question]]]]]
        String stream = client.toBlocking().retrieve(HttpRequest.POST("/a2a", body).header("A2A-Version", "1.0")
                .accept(MediaType.TEXT_EVENT_STREAM_TYPE).bearerAuth(key), String)
        stream.readLines().findAll { it.startsWith("data:") }
                .collect { [data: io.micronaut.serde.ObjectMapper.getDefault().readValue(it.substring(5).trim(), Map)] }
    }

    private Map a2aRpc(String key, String method, Map params) {
        client.toBlocking().retrieve(HttpRequest.POST("/a2a", [jsonrpc: "2.0", id: 1, method: method, params: params])
                .header("A2A-Version", "1.0").bearerAuth(key), Map)
    }

    private static Map parse(Object json) {
        io.micronaut.serde.ObjectMapper.getDefault().readValue(json as String, Map)
    }

    /** ask_assistant at the tenant's MCP server: the result's text. */
    private String mcp(String key, String question) {
        def message = [jsonrpc: "2.0", id: UUID.randomUUID().toString(), method: "tools/call",
                       params : [name: "ask_assistant", arguments: [question: question]]]
        try {
            def body = client.toBlocking().retrieve(HttpRequest.POST("/mcp", message).bearerAuth(key), Map)
            (body.result.content as List<Map>)*.text.join("\n")
        } catch (HttpClientResponseException e) {
            throw new AssertionError("MCP refused: " + e.response.getBody(String).orElse(""))
        }
    }
}
