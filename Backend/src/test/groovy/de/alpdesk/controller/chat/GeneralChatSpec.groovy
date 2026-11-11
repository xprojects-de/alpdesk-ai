package de.alpdesk.controller.chat

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * A general chat asks topic tenants (roadmap point 51): tenant O's connected software is the MCP server of topic A of
 * the same installation - this spec's own server, reached with A's MCP key - and O's user reaches it through a group.
 * No real model: the fake calls the topic's tools when the question names them.
 */
@MicronautTest(transactional = false)
class GeneralChatSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    EmbeddedServer server

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "a user of the general chat finds topic A's text through A's MCP server - the result names its topic"() {
        given:
        def a = topic("Wartung", "wartung.txt", "Die Wartung der Presse ist jeden Montag um sieben Uhr.")
        def general = generalChat([a])

        when:
        def answer = api.ask(general.user.token as String, [modelId: general.chat.id,
                message: 'Wann ist die Wartung? [[call Thema_Wartung__search_knowledge {"query":"Wartung der Presse"}]]'])
                .answer as Map

        then: "A's passage came back, named after its topic"
        def result = toolResult("Thema_Wartung__search_knowledge")
        result.startsWith("[Thema_Wartung] --- From \"wartung.txt\" (document ${a.document.id}) ---")
        result.contains("Die Wartung der Presse ist jeden Montag um sieben Uhr.")
        answer.content.contains("Die Wartung der Presse ist jeden Montag um sieben Uhr.")

        and: "the topic's description reached the general chat's model with the tool"
        (fake.chats.last().toolSpecifications as List<dev.langchain4j.agent.tool.ToolSpecification>)
                .find { it.name() == "Thema_Wartung__search_knowledge" }.description()
                .endsWith("It knows: Wartungspläne der Wartung")

        and: "the general chat, without documents of its own, is told the topics' passages are documents too"
        systemPrompt().contains("The company has no documents of its own to search")
        systemPrompt().contains("Passages a software's search tool returns are the\ncompany's documents too")
        systemPrompt().contains("a document's id holds only within the software that gave it")
    }

    def "read_document reads on in A's document by its id - the result names its topic"() {
        given:
        def a = topic("Wartung", "wartung.txt", "Die Wartung der Presse ist jeden Montag um sieben Uhr.")
        def general = generalChat([a])

        when:
        api.ask(general.user.token as String, [modelId: general.chat.id,
                message: "Lies weiter [[call Thema_Wartung__read_document {\"document\":${a.document.id}}]]".toString()])

        then:
        def result = toolResult("Thema_Wartung__read_document")
        result.startsWith("[Thema_Wartung] ")
        result.contains("Die Wartung der Presse ist jeden Montag um sieben Uhr.")
    }

    def "two topics answer each their own text - ids count per topic: A's second document is not found at B"() {
        given: "A with two documents, B with one - each tenant database counts its own ids"
        def a = topic("Wartung", "wartung.txt", "Die Wartung der Presse ist jeden Montag um sieben Uhr.")
        def second = api.awaitDocument(a.admin as String,
                api.upload(a.admin as String, "pruefung.txt", "Die Prüfung der Presse ist jährlich im März.").id)
        def b = topic("Einkauf", "einkauf.txt", "Bestellungen über 5000 Euro gibt die Leitung frei.")
        def general = generalChat([a, b])
        def token = general.user.token as String

        when:
        api.ask(token, [modelId: general.chat.id,
                message: 'Freigabe? [[call Thema_Einkauf__search_knowledge {"query":"Bestellungen Freigabe"}]]'])

        then:
        def fromB = toolResult("Thema_Einkauf__search_knowledge")
        fromB.startsWith("[Thema_Einkauf] ")
        fromB.contains("Bestellungen über 5000 Euro")
        !fromB.contains("Wartung der Presse")

        when:
        api.ask(token, [modelId: general.chat.id,
                message: "Lies [[call Thema_Einkauf__read_document {\"document\":${second.id}}]]".toString()])

        then: "B does not know it - and never answers with A's text"
        def wrong = toolResult("Thema_Einkauf__read_document")
        wrong.contains("[Thema_Einkauf] Document ${second.id} is not found")
        wrong.contains("is not found")
        !wrong.contains("Prüfung der Presse")
    }

    def "a user in no group gets no topic - and the prompt of a chat without software stays as it was"() {
        given:
        def a = topic("Wartung", "wartung.txt", "Die Wartung der Presse ist jeden Montag um sieben Uhr.")
        def general = generalChat([a])
        def outsider = api.user(general.tenant.id)

        when:
        api.ask(outsider.token as String, [modelId: general.chat.id,
                message: 'Wann? [[call Thema_Wartung__search_knowledge {"query":"Wartung"}]]'])

        then:
        !(fake.chats.last().tools as List<String>).any { it.startsWith("Thema_") }
        systemPrompt().contains("The company has no documents to search")
        !systemPrompt().contains("Passages a software's search tool returns")
        (api.call(outsider.token as String, "GET", "/tenant/chat/software") as List) == []
    }

    def "a topic switched off for the question offers none of its tools"() {
        given:
        def a = topic("Wartung", "wartung.txt", "Die Wartung der Presse ist jeden Montag um sieben Uhr.")
        def general = generalChat([a])

        when:
        api.ask(general.user.token as String, [modelId: general.chat.id, excludedConnectionIds: [general.connections[0].id],
                message: 'Wann? [[call Thema_Wartung__search_knowledge {"query":"Wartung"}]]'])

        then:
        !(fake.chats.last().tools as List<String>).any { it.startsWith("Thema_") }
        !fake.chats.last().messages.any { it instanceof ToolExecutionResultMessage }
    }

    def "A's key has no software - asked through it, A reaches nothing of the general chat (no loop)"() {
        given:
        def a = topic("Wartung", "wartung.txt", "Die Wartung der Presse ist jeden Montag um sieben Uhr.")
        generalChat([a])

        when:
        def tools = client.toBlocking().retrieve(HttpRequest.POST("/mcp", [jsonrpc: "2.0", id: 1, method: "tools/list"])
                .bearerAuth(a.key as String), Map).result.tools as List<Map>

        then:
        !tools.any { (it.name as String).contains("__") }
        tools*.name.containsAll(["search_knowledge", "read_document", "ask_assistant"])
    }

    private String toolResult(String name) {
        def result = fake.chats.collectMany { it.messages as List }
                .findAll { it instanceof ToolExecutionResultMessage && it.toolName() == name }
        assert result, "no result of $name"
        (result.last() as ToolExecutionResultMessage).text()
    }

    private String systemPrompt() {
        (fake.chats.last().messages[0] as SystemMessage).text()
    }

    /** A topic tenant with one document and an MCP key "Allgemeiner Chat" with a description, no software. */
    private Map topic(String name, String fileName, String text) {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        def document = api.awaitDocument(admin, api.upload(admin, fileName, text).id)
        def key = (api.call(admin, "POST", "/tenant/admin/mcp-keys",
                [name: "Allgemeiner Chat", description: "Wartungspläne der " + name]) as Map).plainKey as String
        [name: name, tenant: tenant, admin: admin, document: document, key: key]
    }

    /**
     * The general chat: a tenant with a connection per topic to this installation's own /mcp with the topic's key -
     * search_knowledge and read_document turned on - and a user with READ on each through a group.
     */
    private Map generalChat(List<Map> topics) {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = api.chatModel()
        api.allow(tenant.id, chat)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        def user = api.user(tenant.id)
        def connections = topics.collect { topic ->
            def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: ("Thema_" + topic.name).toString(),
                    baseUrl: "http://127.0.0.1:${server.port}/mcp".toString(), apiKey: topic.key]) as Map
            (connection.tools as List<Map>).findAll { it.name in ["search_knowledge", "read_document"] }.each {
                api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${it.id}", [enabled: true])
            }
            api.grant(admin, connection.id, user.id, "READ")
            connection
        }
        [tenant: tenant, admin: admin, chat: chat, user: user, connections: connections]
    }
}
