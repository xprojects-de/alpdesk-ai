package de.alpdesk.controller.mcp

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.mcp.FakeMcpSoftware
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.serde.ObjectMapper
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * The tenant's MCP server: an MCP client (Claude Code) asks the tenant's assistant with the tenant's one MCP key - like a
 * user who sees no documents. Nothing is stored but the usage; the key reaches nothing but /mcp.
 */
@MicronautTest(transactional = false)
class McpSpec extends Specification {

    static final Path STORAGE = Path.of("build/test-storage")

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Shared
    AiApi api

    /** Through the pool itself: outside a transaction the injected DataSource hands out no connection. */
    DataSource managementDatabase

    @Inject
    void setManagementDatabase(DataSource dataSource) {
        managementDatabase = DelegatingDataSource.unwrapDataSource(dataSource)
    }

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "the tenant admin and the admin keep several named MCP keys; renewing one ends only its old secret, deleting one ends it"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)

        expect:
        api.call(admin, "GET", "/tenant/admin/mcp-keys") == []

        when:
        def development = api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "  Entwicklung "]) as Map
        def management = api.admin("POST", "/admin/tenants/${tenant.id}/mcp-keys", [name: "Geschäftsleitung"]) as Map
        def listed = api.call(admin, "GET", "/tenant/admin/mcp-keys") as List<Map>

        then: "each key works and is listed without its secret"
        development.plainKey.startsWith("mcp_")
        listed*.name == ["Entwicklung", "Geschäftsleitung"]
        listed*.keyPrefix == [development.key.keyPrefix, management.key.keyPrefix]
        listed.every { !it.containsKey("plainKey") && it.webSearch == false && it.areaIds == [] }
        api.admin("GET", "/admin/tenants/${tenant.id}/mcp-keys") == listed
        rpc(development.plainKey, "ping").result == [:]
        rpc(management.plainKey, "ping").result == [:]

        when:
        def renewed = api.call(admin, "POST", "/tenant/admin/mcp-keys/${development.key.id}/renew") as Map

        then: "only the renewed key's old secret stops working - same key, new secret"
        renewed.key.id == development.key.id
        renewed.key.name == "Entwicklung"
        renewed.key.keyPrefix != development.key.keyPrefix
        status(development.plainKey, "ping") == HttpStatus.UNAUTHORIZED
        rpc(renewed.plainKey, "ping").result == [:]
        rpc(management.plainKey, "ping").result == [:]

        and: "it keeps its creation and its place in the list - its last use starts anew with the new secret"
        // Compared with the listed value: that one comes from the database, which keeps microseconds.
        renewed.key.createdAt == listed[0].createdAt
        renewed.key.lastUsedAt == null
        (api.call(admin, "GET", "/tenant/admin/mcp-keys") as List<Map>)*.name == ["Entwicklung", "Geschäftsleitung"]

        when:
        def deleted = api.status(admin, "DELETE", "/tenant/admin/mcp-keys/${development.key.id}")

        then:
        deleted == HttpStatus.NO_CONTENT
        status(renewed.plainKey, "ping") == HttpStatus.UNAUTHORIZED
        rpc(management.plainKey, "ping").result == [:]
        api.status(admin, "DELETE", "/tenant/admin/mcp-keys/${development.key.id}") == HttpStatus.NOT_FOUND
        api.status(api.adminToken, "DELETE", "/admin/tenants/${tenant.id}/mcp-keys/${management.key.id}") == HttpStatus.NO_CONTENT
        status(management.plainKey, "ping") == HttpStatus.UNAUTHORIZED
        api.status(api.adminToken, "POST", "/admin/tenants/999999/mcp-keys", [name: "X"]) == HttpStatus.NOT_FOUND
        api.status(api.adminToken, "GET", "/admin/tenants/999999/mcp-keys") == HttpStatus.NOT_FOUND
    }

    def "a key needs a name: #reason"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def key = api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map

        expect:
        api.status(admin, "POST", "/tenant/admin/mcp-keys", body) == HttpStatus.BAD_REQUEST
        api.error(admin, "POST", "/tenant/admin/mcp-keys", body) == "The key's name is required"
        api.status(admin, "PUT", "/tenant/admin/mcp-keys/${key.key.id}", [name: "  "]) == HttpStatus.BAD_REQUEST

        where:
        reason        | body
        "left out"    | [webSearch: true]
        "blank"       | [name: "   "]
    }

    def "a key is renamed, its web search switched - left out, a setting stays"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def key = (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude", webSearch: true]) as Map).key

        when:
        def renamed = api.call(admin, "PUT", "/tenant/admin/mcp-keys/${key.id}", [name: "Werkstatt"]) as Map
        def switched = api.admin("PUT", "/admin/tenants/${tenant.id}/mcp-keys/${key.id}", [webSearch: false]) as Map

        then:
        key.webSearch == true
        renamed.name == "Werkstatt"
        renamed.webSearch == true
        switched.name == "Werkstatt"
        switched.webSearch == false
    }

    def "a key's description is saved, changed, kept when left out and removed when empty - by the tenant admin and the admin"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)

        when:
        def key = (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Allgemein", description: "  Wartung und Montage  "]) as Map).key
        def plain = (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Ohne"]) as Map).key
        def renamed = api.call(admin, "PUT", "/tenant/admin/mcp-keys/${key.id}", [name: "Technik"]) as Map
        def changed = api.admin("PUT", "/admin/tenants/${tenant.id}/mcp-keys/${key.id}", [description: "Nur Montage"]) as Map
        def listed = (api.call(admin, "GET", "/tenant/admin/mcp-keys") as List<Map>).find { it.id == key.id }
        def emptied = api.call(admin, "PUT", "/tenant/admin/mcp-keys/${key.id}", [description: " "]) as Map

        then:
        key.description == "Wartung und Montage"
        plain.description == null
        renamed.description == "Wartung und Montage"
        changed.description == "Nur Montage"
        listed.description == "Nur Montage"
        emptied.description == null
    }

    def "a key's description has at most 500 characters"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def key = (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map).key

        expect:
        api.status(admin, "POST", "/tenant/admin/mcp-keys", [name: "Zu lang", description: "x" * 501]) == HttpStatus.BAD_REQUEST
        api.status(admin, "PUT", "/tenant/admin/mcp-keys/${key.id}", [description: "x" * 501]) == HttpStatus.BAD_REQUEST
        api.status(admin, "PUT", "/tenant/admin/mcp-keys/${key.id}", [description: "x" * 500]) == HttpStatus.OK
        api.status(api.adminToken, "POST", "/admin/tenants/${tenant.id}/mcp-keys", [name: "Zu lang", description: "x" * 501]) == HttpStatus.BAD_REQUEST
    }

    def "a key's description goes to its own clients only: first in the instructions and in search_knowledge and ask_assistant"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def described = (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Allgemein",
                description: "Wartungspläne der Maschinen und Montageanleitungen"]) as Map).plainKey as String
        def plain = mcpKey(tenant)

        when:
        def server = initialize(described).result
        def tools = rpc(described, "tools/list").result.tools as List<Map>
        def plainServer = initialize(plain).result
        def plainTools = rpc(plain, "tools/list").result.tools as List<Map>

        then:
        (server.instructions as String).startsWith("This access knows: Wartungspläne der Maschinen und Montageanleitungen\n\n")
        (tools.find { it.name == "search_knowledge" }.description as String).endsWith(" It knows: Wartungspläne der Maschinen und Montageanleitungen")
        (tools.find { it.name == "ask_assistant" }.description as String).endsWith(" It knows: Wartungspläne der Maschinen und Montageanleitungen")
        !(tools.find { it.name == "read_document" }.description as String).contains("It knows")
        !(tools.find { it.name == "list_templates" }.description as String).contains("It knows")

        and: "another key of the same tenant without one is unchanged"
        !(plainServer.instructions as String).contains("This access knows")
        plainTools.every { !(it.description as String).contains("It knows") }
    }

    def "what the assistant offers beyond the documents follows the key: #offer"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        if (entry) {
            api.allow(tenant.id, api.admin("POST", "/admin/models", [provider: "TAVILY", kind: "WEB_SEARCH",
                    modelName: "basic-" + AiApi.unique(), displayName: "Websuche " + AiApi.unique(), inputPrice: 8,
                    outputPrice: 0]) as Map)
        }
        if (sandbox) {
            api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name,
                    sandboxUrls: "http://alpdesk-ai-sandbox-" + AiApi.unique() + ":8080", sandboxToken: "t" * 40])
        }
        def connectionIds = []
        if (software) {
            // Named only with a reading tool turned on - what ask_assistant is offered.
            def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections",
                    [name: "Buchhaltung", baseUrl: new FakeMcpSoftware().url()]) as Map
            connectionIds << connection.id
            if (software == "on") {
                def find = (connection.tools as List<Map>).find { it.name == "find_invoice" }
                api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${find.id}", [enabled: true])
            }
        }
        def key = (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Allgemein", description: "Rechnungen",
                webSearch: web, connectionIds: connectionIds]) as Map).plainKey as String

        when:
        def instructions = initialize(key).result.instructions as String
        def tools = rpc(key, "tools/list").result.tools as List<Map>

        then: "first in the instructions, after the description in ask_assistant - never in search_knowledge"
        instructions.startsWith("This access knows: Rechnungen\n\n" + (offer ? offer + "\n\n" : "") + "Zirmo is")
        (tools.find { it.name == "ask_assistant" }.description as String).endsWith("It knows: Rechnungen" + (offer ? " " + offer : ""))
        (tools.find { it.name == "search_knowledge" }.description as String).endsWith("It knows: Rechnungen")

        where:
        web   | entry | sandbox | software || offer
        false | false | false   | null     || null
        true  | true  | false   | null     || "It can also search the web - hand it those parts of a question that concern the company, with their full context."
        true  | false | false   | null     || null
        false | true  | false   | null     || null
        false | false | true    | null     || "It can also run Python and shell code - hand it those parts of a question that concern the company, with their full context."
        false | false | false   | "on"     || "It can also read the connected software Buchhaltung - hand it those parts of a question that concern the company, with their full context."
        false | false | false   | "off"    || null
        true  | true  | true    | "on"     || "It can also search the web, run Python and shell code and read the connected software Buchhaltung - hand it those parts of a question that concern the company, with their full context."
    }

    def "a key's software is chosen on create and change, left out it stays; another tenant's connection is refused, a deleted one drops"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def software = new FakeMcpSoftware()
        def accounting = api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Buchhaltung", baseUrl: software.url()]) as Map
        def stock = api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Lager", baseUrl: software.url()]) as Map
        def foreign = api.call(api.tenantAdminToken(api.tenant().id), "POST", "/tenant/admin/mcp-connections",
                [name: "Fremd", baseUrl: software.url()]) as Map

        when:
        def created = (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Bank", connectionIds: [accounting.id]]) as Map).key
        def renamed = api.call(admin, "PUT", "/tenant/admin/mcp-keys/${created.id}", [name: "Kaufmann"]) as Map
        def changed = api.admin("PUT", "/admin/tenants/${tenant.id}/mcp-keys/${created.id}",
                [connectionIds: [stock.id, accounting.id]]) as Map

        then:
        created.connectionIds == [accounting.id]
        renamed.connectionIds == [accounting.id]
        changed.connectionIds == [accounting.id, stock.id].sort()
        (api.call(admin, "GET", "/tenant/admin/mcp-keys") as List<Map>)[0].connectionIds == [accounting.id, stock.id].sort()

        and: "another tenant's connection is refused"
        api.status(admin, "POST", "/tenant/admin/mcp-keys", [name: "X", connectionIds: [foreign.id]]) == HttpStatus.BAD_REQUEST
        api.error(admin, "PUT", "/tenant/admin/mcp-keys/${created.id}", [connectionIds: [foreign.id]]) ==
                "Unknown connected software"

        when: "a connection is deleted"
        api.status(admin, "DELETE", "/tenant/admin/mcp-connections/${stock.id}")

        then: "it is gone from the key, the other stays"
        (api.call(admin, "GET", "/tenant/admin/mcp-keys") as List<Map>)[0].connectionIds == [accounting.id]

        when: "the key is renewed"
        def renewed = (api.call(admin, "POST", "/tenant/admin/mcp-keys/${created.id}/renew") as Map).key

        then:
        renewed.connectionIds == [accounting.id]

        cleanup:
        software?.stop()
    }

    def "another tenant's key is not found: it cannot be changed, renewed or deleted"() {
        given:
        def tenant = api.tenant()
        def other = api.tenant()
        def foreign = api.call(api.tenantAdminToken(other.id), "POST", "/tenant/admin/mcp-keys", [name: "Fremd"]) as Map
        def admin = api.tenantAdminToken(tenant.id)

        expect:
        api.status(admin, "PUT", "/tenant/admin/mcp-keys/${foreign.key.id}", [name: "Meins"]) == HttpStatus.NOT_FOUND
        api.status(admin, "POST", "/tenant/admin/mcp-keys/${foreign.key.id}/renew") == HttpStatus.NOT_FOUND
        api.status(admin, "DELETE", "/tenant/admin/mcp-keys/${foreign.key.id}") == HttpStatus.NOT_FOUND
        api.status(api.adminToken, "DELETE", "/admin/tenants/${tenant.id}/mcp-keys/${foreign.key.id}") == HttpStatus.NOT_FOUND
        api.call(admin, "GET", "/tenant/admin/mcp-keys") == []
        rpc(foreign.plainKey, "ping").result == [:]
    }

    def "a user's account says whether the tenant has an MCP key - the page Konto then shows how to connect"() {
        given:
        def tenant = api.tenant()
        def user = api.user(tenant.id)
        def account = { (api.call(user.token as String, "GET", "/tenant/account") as Map).mcpServer }

        expect:
        account() == false

        when:
        def key = api.call(api.tenantAdminToken(tenant.id), "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map

        then:
        account() == true

        when:
        api.status(api.tenantAdminToken(tenant.id), "DELETE", "/tenant/admin/mcp-keys/${key.key.id}")

        then:
        account() == false
    }

    def "the MCP key shows its last use; an inactive or deleted tenant's key ends at once"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def key = (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map).plainKey as String
        def lastUsedAt = { (api.call(admin, "GET", "/tenant/admin/mcp-keys") as List<Map>)[0].lastUsedAt }

        expect: "not used yet - then used"
        lastUsedAt() == null
        rpc(key, "ping").result == [:]
        lastUsedAt() != null

        when: "the tenant is deactivated"
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, active: false])

        then:
        status(key, "ping") == HttpStatus.UNAUTHORIZED

        when: "reactivated, then deleted"
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, active: true])
        def reactivated = rpc(key, "ping").result
        api.status(api.adminToken, "DELETE", "/admin/tenants/${tenant.id}")

        then:
        reactivated == [:]
        status(key, "ping") == HttpStatus.UNAUTHORIZED
    }

    def "the MCP key reaches /mcp only; other keys and logins do not reach /mcp"() {
        given:
        def tenant = api.tenant()
        def mcpKey = (api.call(api.tenantAdminToken(tenant.id), "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map).plainKey as String
        def apiKey = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Software"]).plainKey as String

        expect: "no documents, no chat, no settings with the MCP key"
        api.status(mcpKey, "GET", "/api/v1/documents") == HttpStatus.FORBIDDEN
        api.status(mcpKey, "GET", "/tenant/chat/conversations") == HttpStatus.FORBIDDEN
        api.status(mcpKey, "GET", "/tenant/admin/documents") == HttpStatus.FORBIDDEN

        and: "nobody else reaches /mcp"
        status(apiKey, "ping") == HttpStatus.FORBIDDEN
        status(api.userToken(tenant.id), "ping") == HttpStatus.FORBIDDEN
        status(api.adminToken, "ping") == HttpStatus.FORBIDDEN
        status(null, "ping") == HttpStatus.UNAUTHORIZED
        status("mcp_unknown", "ping") == HttpStatus.UNAUTHORIZED

        and: "a user does not keep the key"
        api.status(api.userToken(tenant.id), "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) == HttpStatus.FORBIDDEN
    }

    def "an MCP client initializes (the newest protocol it asks for that the SDK speaks), is notified and lists its tools"() {
        given:
        def key = mcpKey(api.tenant())

        when:
        def initialized = rpc(key, "initialize", [protocolVersion: "2025-03-26", capabilities: [:],
                                                   clientInfo     : [name: "claude-code", version: "1"]])
        def newer = rpc(key, "initialize", [protocolVersion: "2099-01-01", capabilities: [:],
                                             clientInfo     : [name: "claude-code", version: "1"]])
        def notified = exchange(key, [jsonrpc: "2.0", method: "notifications/initialized"])
        def tools = (rpc(key, "tools/list").result.tools as List<Map>).sort { it.name }

        then:
        initialized.result.protocolVersion == "2025-03-26"
        initialized.result.capabilities.tools != null
        initialized.result.capabilities.prompts != null
        initialized.result.serverInfo.name == "Zirmo"
        newer.result.protocolVersion == "2025-11-25"
        notified.status == HttpStatus.ACCEPTED
        tools*.name == ["ask_assistant", "list_tasks", "list_templates", "read_document", "run_task", "search_knowledge",
                        "task_run"]
        tools[0].inputSchema.required == ["question"]
        (tools[0].inputSchema.get("properties") as Map).keySet() == ["question", "template", "files"] as Set

        and: "the schema is flat - a list's items written out, no reference a client would have to resolve"
        (tools[0].inputSchema.get("properties") as Map).files.items.required == ["name", "content"]
        !ObjectMapper.getDefault().writeValueAsString(tools).contains('$ref')
        tools*.annotations*.readOnlyHint == [true, true, true, true, null, true, true]
        tools*.annotations*.destructiveHint == [false, false, false, false, null, false, false]

        and: "an unknown method or tool is a JSON-RPC error; there is no event stream"
        rpc(key, "resources/list").error.code == -32601
        rpc(key, "tools/call", [name: "delete_document"]).error.code == -32602
        streamStatus(key) == HttpStatus.METHOD_NOT_ALLOWED
    }

    def "the server goes by the tenant's brand - its name, its instructions and its tools; else by the installation's"() {
        given:
        def tenant = api.tenant()
        api.admin("PUT", "/admin/tenants/${tenant.id}/branding", [name: brand])
        def key = mcpKey(tenant)

        when:
        def server = initialize(key).result
        def tools = (rpc(key, "tools/list").result.tools as List<Map>).sort { it.name }

        then:
        server.serverInfo.name == shownAs
        (server.instructions as String).startsWith("$shownAs is the AI assistant of the company")
        (server.instructions as String).contains("When the user names $shownAs")
        (tools[0].description as String).startsWith("Asks $shownAs (the company's assistant) a question.")
        (tools.find { it.name == "list_templates" }.description as String).startsWith("Lists the names of the templates of $shownAs (the company's assistant) - kinds")
        (tools.find { it.name == "search_knowledge" }.description as String).startsWith("Searches the documents of $shownAs - its own knowledge")
        (tools.find { it.name == "read_document" }.description as String).startsWith("Reads on in a document of $shownAs that search_knowledge found")

        and: "for facts the client's model searches and reads itself - ask_assistant for a template, files or the connected software"
        (server.instructions as String).contains("for facts search the documents with search_knowledge, read on with read_document")
        (tools[0].description as String).contains("For facts from the documents prefer search_knowledge and read_document")

        and: "another tenant's client meets its own brand"
        def other = api.tenant()
        api.admin("PUT", "/admin/tenants/${other.id}/branding", [name: "Musterfirma Wissen"])
        initialize(mcpKey(other)).result.serverInfo.name == "Musterfirma Wissen"

        where:
        brand     | shownAs
        "Muster AI" | "Muster AI"
        null      | "Zirmo"
    }

    def "ask_assistant answers with the tenant's default model - nothing is stored but the usage"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))
        def key = mcpKey(tenant)

        when:
        def result = rpc(key, "tools/call", [name: "ask_assistant", arguments: [question: "Wann ist die Wartung?"]]).result

        then:
        !result.isError
        result.content[0].type == "text"
        result.content[0].text.startsWith("Antwort auf: Wann ist die Wartung?")
        fake.chats.last().model == setup.chat.modelName

        and: "no conversation, no title - the file tool is offered: its files go with the answer"
        fake.titles.isEmpty()
        fake.chats.last().tools.contains("create_file")
        def apiKey = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Software"]).plainKey as String
        api.call(apiKey, "GET", "/api/v1/chat/conversations") == []
        api.call(setup.admin, "GET", "/tenant/chat/conversations") == []

        and: "the usage is the tenant's: the answer's two rounds (search, answer) and the question's embedding"
        (api.call(setup.admin, "GET", "/tenant/admin/usage") as List<Map>)
                .collectEntries { [(it.kind): it.calls] } == [CHAT: 2, EMBEDDING: 1]
    }

    def "ask_assistant names the documents it drew on"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))
        api.awaitDocument(setup.admin, api.upload(setup.admin, "wartung.txt", "Die Wartung der Anlage ist jeden Montag.").id)

        when:
        def text = rpc(mcpKey(tenant), "tools/call", [name: "ask_assistant",
                                                      arguments: [question: "Wann ist die Wartung der Anlage?"]]).result.content[0].text

        then:
        text.endsWith("Sources: wartung.txt")
    }

    def "a template is chosen by name; an unknown one is a tool error naming the templates"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))
        api.call(setup.admin, "POST", "/tenant/admin/assistant/templates", [name: "Nachschlagen", instructions: "Antworte knapp."])
        def key = mcpKey(tenant)

        when:
        rpc(key, "tools/call", [name: "ask_assistant", arguments: [question: "Frage", template: "nachschlagen"]])
        def unknown = rpc(key, "tools/call", [name: "ask_assistant", arguments: [question: "Frage", template: "Planer"]]).result
        def templates = rpc(key, "tools/call", [name: "list_templates", arguments: [:]]).result

        then:
        (fake.chats.last().messages[0] as SystemMessage).text().contains("Antworte knapp.")
        unknown.isError
        unknown.content[0].text == 'Unknown template "Planer" - Templates: Nachschlagen'
        templates.content[0].text == "Templates: Nachschlagen"
    }

    def "without a default model, or without a question, ask_assistant is a tool error"() {
        given:
        def tenant = api.tenant()
        chatSetup(tenant)
        def key = mcpKey(tenant)

        when:
        def noModel = rpc(key, "tools/call", [name: "ask_assistant", arguments: [question: "Frage"]]).result
        def noQuestion = rpc(key, "tools/call", [name: "ask_assistant", arguments: [:]]).result

        then:
        noModel.isError
        noModel.content[0].text.startsWith("No default model chosen")
        noQuestion.isError
        fake.chats.isEmpty()
    }

    def "a default model taken from the tenant makes ask_assistant a tool error - no provider is asked"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        def key = mcpKey(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))
        api.admin("PUT", "/admin/tenants/${tenant.id}/models", [modelIds: [setup.embedding.id]])

        when:
        def result = rpc(key, "tools/call", [name: "ask_assistant", arguments: [question: "Frage"]]).result

        then:
        result.isError
        fake.chats.isEmpty()
    }

    def "text files go with the question, each under its name - nothing is kept"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))
        def files = [[name: "src/Control.java", content: "class Control { void start() {} }"],
                     [name: "README.md", content: "# Steuerung"]]

        when:
        def result = rpc(mcpKey(tenant), "tools/call", [name: "ask_assistant",
                                                       arguments: [question: "Entspricht der Code unseren Regeln?", files: files]]).result
        def sent = (fake.chats.first().messages.find { it instanceof UserMessage } as UserMessage).singleText()

        then:
        !result.isError
        sent.startsWith("Entspricht der Code unseren Regeln?")
        sent.contains('=== The file "src/Control.java" (sent with the question) ===\nclass Control { void start() {} }')
        sent.contains('=== The file "README.md" (sent with the question) ===\n# Steuerung')

        and: "no conversation keeps them"
        api.call(setup.admin, "GET", "/tenant/chat/conversations") == []
    }

    def "a file too large for the question is searched and read by the model - over MCP as over /api/v1"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))
        def source = (1..300).collect { it == 120 ? "    // TODO: fix the timeout" : "    int field${it};" }.join("\n")

        when:
        def result = rpc(mcpKey(tenant), "tools/call", [name: "ask_assistant",
                arguments: [question: "Was ist offen? [[search todo]]", files: [[name: "Control.java", content: source]]]]).result

        then:
        !result.isError
        fake.chats.first().tools.containsAll(["search_file", "read_file_lines"])
        result.content[0].text.contains('1 matching line in "Control.java":\n120:     // TODO: fix the timeout')
    }

    /** What breaks the schema the SDK refuses before the tool runs; the rest ChatService checks. */
    def "files that cannot go with a question are a tool error: #reason"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))

        when:
        def result = rpc(mcpKey(tenant), "tools/call", [name: "ask_assistant", arguments: [question: "Frage", files: files]]).result

        then:
        result.isError
        result.content[0].text.startsWith(message)
        fake.chats.isEmpty()

        where:
        reason            | files                                                              || message
        "too many"        | (1..21).collect { [name: "f${it}.txt".toString(), content: "x"] }  || "At most 20 files"
        "no name"         | [[name: " ", content: "x"]]                                        || "Every file needs a name"
        "no content"      | [[name: "a.txt"]]                                                  || "Tool (ask_assistant) input validation failed"
        "too much text"   | [[name: "a.txt", content: "x" * 150_000], [name: "b.txt", content: "y" * 50_001]] || "The files may have at most 200000 characters"
        "not a list"      | "src/Control.java"                                                 || "Tool (ask_assistant) input validation failed"
        "not objects"     | ["src/Control.java"]                                               || "Tool (ask_assistant) input validation failed"
    }

    def "an MCP key reaches a connected software through ask_assistant only once it is given the software - and only to read"() {
        given: "a connected software with its reading and its changing tool on, and a user who may change"
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))
        def software = new FakeMcpSoftware()
        def connection = api.call(setup.admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Buchhaltung", baseUrl: software.url()]) as Map
        (connection.tools as List<Map>).each {
            api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${it.id}", [enabled: true])
        }
        def user = api.user(tenant.id)
        api.grant(setup.admin, connection.id, user.id, "WRITE")
        def created = api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map
        def key = created.plainKey as String
        def otherKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Bank"]) as Map).plainKey as String
        def question = 'Storniere Rechnung 4711. [[call Buchhaltung__cancel_invoice {"number":"4711"}]] ' +
                '[[call Buchhaltung__find_invoice {"number":"4711"}]]'

        expect: "a key without the software: none of its tools is offered, the software is never called"
        created.key.connectionIds == []
        !rpc(key, "tools/call", [name: "ask_assistant", arguments: [question: question]]).result.isError
        !fake.chats.last().tools.any { it.contains("__") }
        software.calls().isEmpty()

        when: "a tenant admin gives the key the software - not passed on to MCP clients, answers only"
        def given = api.call(setup.admin, "PUT", "/tenant/admin/mcp-keys/${created.key.id}",
                [connectionIds: [connection.id]]) as Map
        def answer = rpc(key, "tools/call", [name: "ask_assistant", arguments: [question: question]]).result.content[0].text

        then: "its reading tool is offered and called - never the changing one"
        given.connectionIds == [connection.id]
        fake.chats.last().tools.contains("Buchhaltung__find_invoice")
        !fake.chats.last().tools.contains("Buchhaltung__cancel_invoice")
        answer.contains("(Software: [Buchhaltung] Invoice 4711: 412,50 EUR, paid)")
        software.calls()*.params*.name == ["find_invoice"]

        when: "another key of the tenant, without the software, asks"
        rpc(otherKey, "tools/call", [name: "ask_assistant", arguments: [question: question]])

        then:
        !fake.chats.last().tools.any { it.contains("__") }
        software.calls().size() == 1

        when: "the key's software is taken away again"
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-keys/${created.key.id}", [connectionIds: []])
        rpc(key, "tools/call", [name: "ask_assistant", arguments: [question: question]])

        then:
        !fake.chats.last().tools.any { it.contains("__") }
        software.calls().size() == 1

        cleanup:
        software?.stop()
    }

    def "a file the assistant creates goes with the answer - text as text, anything else as base64; nothing is kept: #format"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))

        when:
        def result = rpc(mcpKey(tenant), "tools/call", [name: "ask_assistant",
                arguments: [question: "Fasse zusammen [[create_file $name $format]]".toString()]]).result

        then: "the model hears the file goes to the asker - from the tool and from its rules"
        (fake.chats.first().messages[0] as SystemMessage).text().contains(
                "Never write a link or path to a created file or image, and do not repeat its content: it goes to the asker")
        !result.isError
        result.content*.type == ["text", "resource"]
        result.content[0].text.contains("It goes to the asker with your answer - do not repeat its content, write no link or path to it.")

        and: "the file, as text or as base64"
        def resource = result.content[1].resource as Map
        resource.uri == "file:///" + fileName
        resource.mimeType == mimeType
        asText ? (resource.text as String).startsWith("# Überarbeitet") : resource.blob != null
        asText || (resource.blob as String).decodeBase64().length > 0

        and: "kept nowhere: no conversation, no stored content"
        api.call(setup.admin, "GET", "/tenant/chat/conversations") == []
        !Files.exists(STORAGE.resolve("tenants/${tenant.id}/chat")) ||
                Files.walk(STORAGE.resolve("tenants/${tenant.id}/chat")).noneMatch { Files.isRegularFile(it) }

        where:
        name          | format || fileName      | mimeType                                                                    | asText
        "notizen.md"  | "MD"   || "notizen.md"  | "text/markdown; charset=utf-8"                                              | true
        "bericht"     | "DOCX" || "bericht.docx" | "application/vnd.openxmlformats-officedocument.wordprocessingml.document" | false
        "bericht.pdf" | "PDF"  || "bericht.pdf" | "application/pdf"                                                           | false
    }

    def "an image the assistant creates goes with the answer as base64 - it is not offered for a next question"() {
        given:
        def tenant = api.tenant()
        def chat = api.chatModel("GEMINI")
        def image = api.imageModel()
        api.allow(tenant.id, chat, image)
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/GEMINI", [apiKey: "own-key-of-GEMINI"])
        api.call(admin, "PUT", "/tenant/admin/assistant", settings(chat.id))

        when:
        def result = rpc(mcpKey(tenant), "tools/call", [name: "ask_assistant",
                arguments: [question: "Mach mir eine Grafik [[create_image]]"]]).result
        def resource = result.content[1].resource as Map

        then:
        result.content*.type == ["text", "resource"]
        resource.uri == "file:///bild.png"
        resource.mimeType == "image/png"
        (resource.blob as String).decodeBase64().length > 0
        result.content[0].text.contains('The file "bild.png" was created. It goes to the asker with your answer')
        !result.content[0].text.contains("From the next question on")
    }

    def "the connected software's files go with the answer where it passes through - its types kept to the list, text files as text"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))
        def software = new FakeMcpSoftware()
        def zip = FakeMcpSoftware.zip("src/Main.java", "class Main {}")
        software.callContent = [[type: "text", text: "Generated"],
                                FakeMcpSoftware.blob("file:///demo-project.zip", "application/zip", zip),
                                [type: "resource", resource: [uri: "file:///Main.java", mimeType: "text/x-java", text: "class Main {}"]],
                                FakeMcpSoftware.blob("file:///page.html", "text/html", "<script>x</script>".getBytes("UTF-8"))]
        def connection = api.call(setup.admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Buchhaltung", baseUrl: software.url()]) as Map
        def find = (connection.tools as List<Map>).find { it.name == "find_invoice" }
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${find.id}", [enabled: true])
        def key = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys",
                [name: "Claude", connectionIds: [connection.id]]) as Map).plainKey as String
        def question = [name: "ask_assistant", arguments: [
                question: 'Erstelle das Projekt [[call Buchhaltung__find_invoice {"number":"4711"}]]']]

        when: "the software answers only (the default): its files stay inside, the model is told"
        def answerOnly = rpc(key, "tools/call", question).result

        then:
        answerOnly.content.findAll { it.type == "resource" }.isEmpty()
        fake.chats.last().messages.toString().contains("Buchhaltung answers only: its files do not go to the asker")

        when: "it passes through to MCP clients"
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/pass-through", [passesThrough: true])
        def result = rpc(key, "tools/call", question).result
        def resources = result.content.findAll { it.type == "resource" }*.resource as List<Map>

        then:
        resources*.uri == ["file:///demo-project.zip", "file:///Main.java", "file:///page.html"]
        resources*.mimeType == ["application/zip", "text/plain", "text/plain"]
        (resources[0].blob as String).decodeBase64() == zip
        resources[1].text == "class Main {}"
        resources[2].text == "<script>x</script>"

        cleanup:
        software?.stop()
    }

    def "the tenant's templates are its prompts - slash commands in the client, named like a command takes a name"() {
        given:
        def tenant = api.tenant()
        api.admin("PUT", "/admin/tenants/${tenant.id}/branding", [name: "Muster AI"])
        def admin = api.tenantAdminToken(tenant.id)
        ["Code-Review", "Prüfung", "code review"].each {
            api.call(admin, "POST", "/tenant/admin/assistant/templates", [name: it, instructions: "Antworte knapp."])
        }
        def key = mcpKey(tenant)

        when:
        def prompts = rpc(key, "prompts/list").result.prompts as List<Map>

        then: "a name taken twice stays the oldest template's, a later one gets its id - adding one moves no command"
        def laterId = (api.call(admin, "GET", "/tenant/admin/assistant/templates") as List<Map>).find { it.name == "code review" }.id
        prompts*.name == ["code-review-$laterId".toString(), "code-review", "pruefung"]
        prompts*.title == ["code review", "Code-Review", "Prüfung"]
        prompts[1].description == 'Asks Muster AI (the company\'s assistant) with its template "Code-Review"'
        prompts[0].arguments*.name == ["question"]
        prompts[0].arguments*.required == [false]

        when:
        def asked = rpc(key, "prompts/get", [name: "pruefung", arguments: [question: "Prüf OrderService.java"]]).result
        def open = rpc(key, "prompts/get", [name: "code-review"]).result

        then: "the client's model is told to ask the assistant with that template"
        asked.messages*.role == ["user"]
        asked.messages[0].content.text == 'Ask Muster AI with the tool ask_assistant and the template "Prüfung". ' +
                "The question: Prüf OrderService.java"
        (open.messages[0].content.text as String).endsWith("The question: what I am working on right now - give the " +
                "full context, and send the files it is about in files.")

        and: "a prompt whose template was deleted since asks without one - an error would end some clients' session"
        rpc(key, "prompts/get", [name: "planer", arguments: [question: "Wie geht das?"]]).result.messages[0].content.text ==
                "Ask Muster AI with the tool ask_assistant. The question: Wie geht das?"

        and: "a request without a name or without params asks without a template, never fails"
        rpc(key, "prompts/get", [arguments: [question: "Wie geht das?"]]).result.messages[0].content.text ==
                "Ask Muster AI with the tool ask_assistant. The question: Wie geht das?"
        (rpc(key, "prompts/get").result.messages[0].content.text as String).startsWith("Ask Muster AI with the tool ask_assistant.")

        and: "another tenant sees only its own templates"
        rpc(mcpKey(api.tenant()), "prompts/list").result.prompts == []
    }

    def "an answer without text is a tool error naming why"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))

        when:
        def result = rpc(mcpKey(tenant), "tools/call", [name: "ask_assistant", arguments: [question: "Log [[empty-length]]"]]).result

        then:
        result.isError
        result.content[0].text.contains("used up its output limit on its reasoning")
    }

    def "a failure of ours in our own tools is a tool error, not a JSON-RPC error - the client's session goes on"() {
        given: "the tenant's provider keys stored so that they cannot be decrypted any more (APP_SECRET_KEY changed)"
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id))
        api.awaitDocument(setup.admin, api.upload(setup.admin, "wartung.txt", "Die Wartung der Anlage ist jeden Montag.").id)
        def key = mcpKey(tenant)
        managementDatabase.connection.withCloseable {
            it.prepareStatement("UPDATE provider_key SET api_key = 'broken' WHERE tenant_id = ${tenant.id}").executeUpdate()
        }

        when:
        def asked = exchange(key, toolCall("ask_assistant", [question: "Wann ist die Wartung?"]))
        def searched = exchange(key, toolCall("search_knowledge", [query: "Wartung"]))

        then: "an answer the client's model reads, without the exception's text"
        [asked, searched].every { it.status == HttpStatus.OK && it.body.error == null && it.body.result.isError }
        asked.body.result.content[0].text == "The tool ask_assistant failed - try again later"
        searched.body.result.content[0].text == "The tool search_knowledge failed - try again later"

        and: "the session goes on"
        rpc(key, "tools/call", [name: "list_templates", arguments: [:]]).result.content[0].text == "The assistant has no templates."
    }

    def "the default model must be a chat model of the tenant; the settings offer its chat models"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        def foreign = api.chatModel()

        expect:
        (api.call(setup.admin, "GET", "/tenant/admin/assistant/models") as List<Map>)*.id == [setup.chat.id]
        (api.admin("GET", "/admin/tenants/${tenant.id}/assistant/models") as List<Map>)*.id == [setup.chat.id]
        api.status(setup.admin, "PUT", "/tenant/admin/assistant", settings(foreign.id)) == HttpStatus.BAD_REQUEST
        api.status(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.embedding.id)) == HttpStatus.BAD_REQUEST
        (api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(setup.chat.id)) as Map).defaultModelId == setup.chat.id
    }

    private static Map settings(Object defaultModelId) {
        [instructions: "", answerSource: "DOCUMENTS_ONLY", creativity: "MEDIUM", reasoning: "LOW", searchResults: 5,
         defaultModelId: defaultModelId]
    }

    private String mcpKey(Map tenant) {
        (api.call(api.tenantAdminToken(tenant.id), "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map).plainKey
    }

    /** A JSON-RPC request with a fresh id: the parsed response. */
    private Map initialize(String key) {
        rpc(key, "initialize", [protocolVersion: "2025-03-26", capabilities: [:], clientInfo: [name: "claude-code", version: "1"]])
    }

    private Map rpc(String key, String method, Map params = null) {
        Map message = [jsonrpc: "2.0", id: UUID.randomUUID().toString(), method: method]
        if (params != null) {
            message.params = params
        }
        exchange(key, message).body
    }

    private static Map toolCall(String name, Map arguments) {
        [jsonrpc: "2.0", id: UUID.randomUUID().toString(), method: "tools/call", params: [name: name, arguments: arguments]]
    }

    private HttpStatus status(String key, String method) {
        exchange(key, [jsonrpc: "2.0", id: 1, method: method]).status
    }

    private Map exchange(String key, Map message) {
        def request = HttpRequest.POST("/mcp", message)
        if (key) {
            request = request.bearerAuth(key)
        }
        try {
            def response = client.toBlocking().exchange(request, Map)
            [status: response.status, body: response.body.orElse(null)]
        } catch (HttpClientResponseException e) {
            [status: e.status, body: e.response.getBody(Map).orElse(null)]
        }
    }

    private HttpStatus streamStatus(String key) {
        try {
            client.toBlocking().exchange(HttpRequest.GET("/mcp").bearerAuth(key), String).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    /** A tenant with a chat and an embedding model and keys: its tenant admin, the models. */
    private Map chatSetup(Map tenant) {
        def admin = api.tenantAdminToken(tenant.id)
        def chat = api.chatModel()
        def embedding = api.embeddingModel()
        api.allow(tenant.id, chat, embedding)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        [admin: admin, chat: chat, embedding: embedding]
    }
}
