package de.alpdesk.controller.chat

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.mcp.FakeMcpSoftware
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * The connected software a question leaves out (roadmap point 51, docs/architecture/mcp.md "MCP clients"): the user
 * reaches some through its groups and switches some off per conversation - the choice only narrows, is kept with the
 * conversation and returned with it; an id the asker does not reach is refused. The same for the client software with
 * its API key's software.
 */
@MicronautTest(transactional = false)
class SoftwareChoiceSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Shared
    AiApi api

    FakeMcpSoftware first = new FakeMcpSoftware()
    FakeMcpSoftware second = new FakeMcpSoftware()

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def cleanup() {
        first.stop()
        second.stop()
    }

    def "a user lists the software it reaches and leaves one out per question - its tools are not offered"() {
        given:
        def setup = tenantWithTwoTopics()

        expect: "the software the user reaches, by name - with what its server said"
        (api.call(setup.user.token, "GET", "/tenant/chat/software") as List<Map>)*.name == ["Thema_A", "Thema_B"]

        when:
        def answer = api.ask(setup.user.token, [modelId: setup.chat.id, message: "Frage",
                                                excludedConnectionIds: [setup.b.id]])

        then:
        offered().contains("Thema_A__find_invoice")
        !offered().any { it.startsWith("Thema_B__") }
        answer.conversation.excludedConnectionIds == [setup.b.id]
    }

    def "the choice is kept with the conversation: left out it stays, an empty one switches every topic on again"() {
        given:
        def setup = tenantWithTwoTopics()
        def token = setup.user.token as String
        def conversationId = api.ask(token, [modelId: setup.chat.id, message: "Eins",
                                             excludedConnectionIds: [setup.a.id]]).conversation.id

        when: "the next question names none"
        api.ask(token, [modelId: setup.chat.id, conversationId: conversationId, message: "Zwei"])

        then: "the conversation's choice holds - and the list of conversations says so"
        !offered().any { it.startsWith("Thema_A__") }
        (api.call(token, "GET", "/tenant/chat/conversations") as List<Map>)
                .find { it.id == conversationId }.excludedConnectionIds == [setup.a.id]

        when: "an empty list switches every topic on again"
        def again = api.ask(token, [modelId: setup.chat.id, conversationId: conversationId, message: "Drei",
                                    excludedConnectionIds: []])

        then:
        offered().containsAll(["Thema_A__find_invoice", "Thema_B__find_invoice"])
        again.conversation.excludedConnectionIds == []

        and: "a new conversation starts with every topic on"
        api.ask(token, [modelId: setup.chat.id, message: "Neu"])
        offered().containsAll(["Thema_A__find_invoice", "Thema_B__find_invoice"])
    }

    def "a connection deleted or taken from the user's groups since is dropped on reading, never an error"() {
        given:
        def setup = tenantWithTwoTopics()
        def token = setup.user.token as String
        def conversationId = api.ask(token, [modelId: setup.chat.id, message: "Eins",
                                             excludedConnectionIds: [setup.a.id, setup.b.id]]).conversation.id

        when:
        assert api.status(setup.admin, "DELETE", "/tenant/admin/mcp-connections/${setup.a.id}") == HttpStatus.NO_CONTENT
        api.setLevel(setup.admin, setup.groupB, setup.b.id, null)
        def next = api.ask(token, [modelId: setup.chat.id, conversationId: conversationId, message: "Zwei"])

        then:
        next.conversation.excludedConnectionIds == []
        (api.call(token, "GET", "/tenant/chat/conversations") as List<Map>)
                .find { it.id == conversationId }.excludedConnectionIds == []
        (api.call(token, "GET", "/tenant/chat/software") as List<Map>) == []
    }

    def "an id the user does not reach is refused - unknown, another tenant's, not in its groups: #reason"() {
        given:
        def setup = tenantWithTwoTopics()
        def other = tenantWithTwoTopics()
        def notGranted = api.call(setup.admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Ohne_Gruppe", baseUrl: first.url()]) as Map
        def ids = [unknown: 999_999, foreign: other.a.id, ungranted: notGranted.id, none: null]

        when:
        def status = api.status(setup.user.token as String, "POST", "/tenant/chat/stream",
                [modelId: setup.chat.id, message: "Frage", excludedConnectionIds: [ids[reason]]])
        def error = api.error(setup.user.token as String, "POST", "/tenant/chat/stream",
                [modelId: setup.chat.id, message: "Frage", excludedConnectionIds: [ids[reason]]])

        then:
        status == HttpStatus.BAD_REQUEST
        error == "Unknown connected software"
        fake.chats.isEmpty()

        where:
        reason << ["unknown", "foreign", "ungranted", "none"]
    }

    def "another user of the tenant sees neither the conversation nor its choice"() {
        given:
        def setup = tenantWithTwoTopics()
        def conversationId = api.ask(setup.user.token as String, [modelId: setup.chat.id, message: "Eins",
                                                                 excludedConnectionIds: [setup.a.id]]).conversation.id
        def other = api.user(setup.tenant.id)

        expect:
        !(api.call(other.token as String, "GET", "/tenant/chat/conversations") as List<Map>).any { it.id == conversationId }
        api.status(other.token as String, "POST", "/tenant/chat/stream", [modelId: setup.chat.id, message: "Zwei",
                conversationId: conversationId, excludedConnectionIds: []]) == HttpStatus.NOT_FOUND
        (api.call(other.token as String, "GET", "/tenant/chat/software") as List<Map>) == []
    }

    def "the client software lists its key's software and leaves one out - kept with its conversation"() {
        given:
        def setup = tenantWithTwoTopics()
        def key = (api.call(setup.admin, "POST", "/tenant/admin/api-keys",
                [name: "Eigenes Frontend", connectionIds: [setup.a.id, setup.b.id]]) as Map).plainKey as String

        expect:
        (api.call(key, "GET", "/api/v1/software") as List<Map>)*.id == [setup.a.id, setup.b.id]

        when:
        def answer = api.call(key, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: "Frage",
                                                          excludedConnectionIds: [setup.a.id]]) as Map

        then:
        !offered().any { it.startsWith("Thema_A__") }
        offered().contains("Thema_B__find_invoice")
        answer.conversation.excludedConnectionIds == [setup.a.id]

        when: "the next question of the conversation names none"
        api.call(key, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: "Weiter",
                                               conversationId: answer.conversation.id])

        then:
        !offered().any { it.startsWith("Thema_A__") }
        (api.call(key, "GET", "/api/v1/chat/conversations") as List<Map>)
                .find { it.id == answer.conversation.id }.excludedConnectionIds == [setup.a.id]
    }

    def "a key without history takes the choice per question; an id not of the key is refused"() {
        given:
        def setup = tenantWithTwoTopics()
        def key = (api.call(setup.admin, "POST", "/tenant/admin/api-keys",
                [name: "Ohne Verlauf", keepsHistory: false, connectionIds: [setup.a.id]]) as Map).plainKey as String

        when:
        api.call(key, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: "Frage", excludedConnectionIds: [setup.a.id]])

        then:
        !offered().any { it.startsWith("Thema_A__") }

        when:
        api.call(key, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: "Wieder"])

        then:
        offered().contains("Thema_A__find_invoice")

        and: "Thema_B is not the key's"
        api.status(key, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: "Frage",
                                                 excludedConnectionIds: [setup.b.id]]) == HttpStatus.BAD_REQUEST
        api.status(key, "POST", "/api/v1/chat/stream", [modelId: setup.chat.id, message: "Frage",
                                                        excludedConnectionIds: [setup.b.id]]) == HttpStatus.BAD_REQUEST
    }

    private List<String> offered() {
        fake.chats.last().tools as List<String>
    }

    /** A tenant with two topics as connected software, their reading tool on, a user with READ on both (one group each). */
    private Map tenantWithTwoTopics() {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = api.chatModel()
        api.allow(tenant.id, chat)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        def a = connect(admin, "Thema_A", first)
        def b = connect(admin, "Thema_B", second)
        def user = api.user(tenant.id)
        def groupA = api.grant(admin, a.id, user.id, "READ")
        def groupB = api.grant(admin, b.id, user.id, "READ")
        [tenant: tenant, admin: admin, chat: chat, a: a, b: b, user: user, groupA: groupA, groupB: groupB]
    }

    private Map connect(String admin, String name, FakeMcpSoftware software) {
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: name, baseUrl: software.url()]) as Map
        def find = (connection.tools as List<Map>).find { it.name == "find_invoice" }
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${find.id}", [enabled: true])
        connection
    }
}
