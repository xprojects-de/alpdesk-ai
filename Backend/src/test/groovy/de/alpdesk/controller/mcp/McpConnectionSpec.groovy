package de.alpdesk.controller.mcp

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.chat.ChatResult
import de.alpdesk.services.chat.ChatService
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.repositories.chat.ConversationFileRepository
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.mcp.FakeMcpSoftware
import de.alpdesk.services.sandbox.FakeSandboxes
import de.alpdesk.services.tenant.TenantDatabases
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The tenant's connected software (MCP clients): its tools are read when the connection is made and stay off until a
 * tenant admin turns them on; a user reaches them in the chat only with a level for the connection - for now the
 * reading ones only. Another tenant's connections answer 404.
 */
@MicronautTest(transactional = false)
class McpConnectionSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    TenantDatabases databases

    @Inject
    TenantRepository tenants

    @Inject
    ConversationFileRepository fileRows

    @Inject
    FakeSandboxes sandboxes

    @Inject
    ChatService chatService

    @Shared
    AiApi api

    FakeMcpSoftware software = new FakeMcpSoftware()

    /** Through the pool itself: outside a transaction the injected DataSource hands out no connection. */
    DataSource managementDatabase

    @Inject
    void setManagementDatabase(DataSource dataSource) {
        managementDatabase = DelegatingDataSource.unwrapDataSource(dataSource)
    }

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
        sandboxes.reset()
    }

    def cleanup() {
        software.stop()
    }

    def "a connection reads the software's tools at once - all off, the changing ones marked; the key stays hidden"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        software.key = "software-key-123"

        when:
        def created = api.call(admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Buchhaltung", baseUrl: software.url(), apiKey: "software-key-123"]) as Map

        then:
        created.name == "Buchhaltung"
        created.keyHint == "…-123"
        !created.containsKey("apiKey")
        created.toolsError == null
        created.toolsLoadedAt != null
        (created.tools as List<Map>).collect { [it.name, it.changes, it.enabled, it.offered] } ==
                [["cancel_invoice", true, false, true], ["find_invoice", false, false, true]]

        and: "the admin sees the same connection"
        (api.admin("GET", "/admin/tenants/${tenant.id}/mcp-connections") as List<Map>)*.name == ["Buchhaltung"]
    }

    def "our client introduces itself to the software with the tenant's brand, else the installation's"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        api.admin("PUT", "/admin/tenants/${tenant.id}/branding", [name: brand])

        when:
        api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Buchhaltung", baseUrl: software.url()])

        then:
        (software.received.find { it.method == "initialize" }.params as Map).clientInfo.name == introducedAs

        where:
        brand     | introducedAs
        "Muster AI" | "Muster AI"
        null      | "Zirmo"
    }

    def "a software that cannot be read keeps the connection and says why; reload tries again"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        software.key = "right-key-1234"

        when:
        def created = api.call(admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Buchhaltung", baseUrl: software.url(), apiKey: "wrong-key-1234"]) as Map

        then:
        created.toolsError == "Buchhaltung refused our key - check it in the connection"
        created.tools == []

        when:
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${created.id}",
                [name: "Buchhaltung", baseUrl: software.url(), apiKey: "right-key-1234"])
        def reloaded = api.call(admin, "POST", "/tenant/admin/mcp-connections/${created.id}/reload") as Map

        then:
        reloaded.toolsError == null
        (reloaded.tools as List<Map>)*.name == ["cancel_invoice", "find_invoice"]
    }

    def "reloading keeps what the admin chose, brings new tools in off and marks dropped ones as no longer offered"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def connection = connect(admin)
        def find = tool(connection, "find_invoice")
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${find.id}", [enabled: true])
        software.tools = [software.tools[0], [name: "list_orders", description: "Lists orders",
                                              inputSchema: [type: "object"], annotations: [readOnlyHint: true]]]

        when:
        def reloaded = api.call(admin, "POST", "/tenant/admin/mcp-connections/${connection.id}/reload") as Map

        then:
        (reloaded.tools as List<Map>).collect { [it.name, it.enabled, it.offered] } ==
                [["cancel_invoice", false, false], ["find_invoice", true, true], ["list_orders", false, true]]
    }

    def "a change made while a reload waits for the software stays - the reload brings tools, never an old setting back"() {
        given: "a connection passed on to MCP clients, and a software that holds its tool list back"
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def connection = connect(admin)
        def base = "/tenant/admin/mcp-connections/${connection.id}"
        api.call(admin, "PUT", "$base/pass-through", [passesThrough: true])
        def listing = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        software.nextCursor = { String cursor ->
            listing.countDown()
            release.await(30, TimeUnit.SECONDS)
            null
        }

        when: "passing it on is switched off while the reload waits"
        def reloaded = null
        def reload = Thread.start { reloaded = api.call(admin, "POST", "$base/reload") as Map }
        listing.await(30, TimeUnit.SECONDS)
        api.call(admin, "PUT", "$base/pass-through", [passesThrough: false])
        release.countDown()
        reload.join(30_000)

        then:
        (reloaded.tools as List<Map>)*.name == ["cancel_invoice", "find_invoice"]
        (api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>)[0].passesThrough == false
    }

    def "a reload whose connection was moved to another address meanwhile stores nothing of the old software"() {
        given: "a connection whose software holds its tool list back, and another software with another tool"
        def admin = api.tenantAdminToken(api.tenant().id)
        def connection = connect(admin)
        def base = "/tenant/admin/mcp-connections/${connection.id}"
        def other = new FakeMcpSoftware()
        other.tools = [[name: "list_orders", description: "Lists orders", annotations: [readOnlyHint: true],
                        inputSchema: [type: "object", properties: [:]]]]
        def listing = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        software.nextCursor = { String cursor ->
            listing.countDown()
            release.await(30, TimeUnit.SECONDS)
            null
        }

        when: "the address is changed while the reload waits for the old software"
        def reload = Thread.start { api.call(admin, "POST", "$base/reload") }
        listing.await(30, TimeUnit.SECONDS)
        api.call(admin, "PUT", base, [name: "Buchhaltung", baseUrl: other.url()])
        release.countDown()
        reload.join(30_000)

        then: "the tools are the new software's alone"
        def stored = (api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>)[0]
        stored.baseUrl == other.url()
        (stored.tools as List<Map>)*.name == ["list_orders"]

        cleanup:
        other.stop()
    }

    def "#change made while a reload writes the same connection keeps what the reload wrote - it reads after it, not before"() {
        given: "a connection passed on to MCP clients, and a reload's write holding its row"
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def connection = connect(admin)
        def base = "/tenant/admin/mcp-connections/${connection.id}"
        def tool = (connection.tools as List<Map>).find { it.name == "find_invoice" }
        api.call(admin, "PUT", "$base/pass-through", [passesThrough: true])
        def locked = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def reload = Thread.start {
            managementDatabase.connection.withCloseable { db ->
                db.autoCommit = false
                db.prepareStatement("SELECT id FROM mcp_connection WHERE id = ${connection.id} FOR NO KEY UPDATE").executeQuery()
                db.prepareStatement("UPDATE mcp_connection SET instructions = 'Neu gelesen' WHERE id = ${connection.id}").executeUpdate()
                db.prepareStatement("UPDATE mcp_tool SET changes = TRUE WHERE id = ${tool.id}").executeUpdate()
                locked.countDown()
                release.await(30, TimeUnit.SECONDS)
                db.commit()
            }
        }
        locked.await(30, TimeUnit.SECONDS)

        when:
        def writer = Thread.start { write(admin, base, tool, software.url()) }
        sleep(500)
        release.countDown()
        reload.join(30_000)
        writer.join(30_000)
        def after = (api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>)[0]

        then:
        after.instructions == "Neu gelesen"
        (after.tools as List<Map>).find { it.name == "find_invoice" }.changes == true

        where:
        change                    | write
        "the pass-through switch" | { String token, String path, Map found, String url -> api.call(token, "PUT", "$path/pass-through", [passesThrough: false]) }
        "a tool's switch"         | { String token, String path, Map found, String url -> api.call(token, "PUT", "$path/tools/${found.id}", [enabled: true]) }
        "a rename"                | { String token, String path, Map found, String url -> api.call(token, "PUT", path, [name: "Faktura", baseUrl: url]) }
    }

    def "a reload keeps the admin's correction of 'changes' - unless the server changed its word"() {
        given: "cancel_invoice has no readOnlyHint; the admin knows it only reads and corrects it"
        def admin = api.tenantAdminToken(api.tenant().id)
        def connection = connect(admin)
        def base = "/tenant/admin/mcp-connections/${connection.id}"
        api.call(admin, "PUT", "$base/tools/${tool(connection, "cancel_invoice").id}", [changes: false])

        when: "reloaded with the server saying the same"
        def same = api.call(admin, "POST", "$base/reload") as Map

        then:
        !tool(same, "cancel_invoice").changes
        !tool(same, "find_invoice").changes

        when: "a software update drops find_invoice's readOnlyHint - it changes something now"
        software.tools = [[name: "find_invoice", description: "Finds and books an invoice",
                           inputSchema: [type: "object", properties: [number: [type: "string"]]]], software.tools[1]]
        def updated = api.call(admin, "POST", "$base/reload") as Map

        then: "it is asked before every call again; the admin's correction of the other stays"
        tool(updated, "find_invoice").changes
        !tool(updated, "cancel_invoice").changes
    }

    def "invalid connections are refused: #reason"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)

        expect:
        api.status(admin, "POST", "/tenant/admin/mcp-connections", body(software)) == HttpStatus.BAD_REQUEST

        where:
        reason                 | body
        "no name"              | { s -> [name: " ", baseUrl: s.url()] }
        "a name too long"      | { s -> [name: "x" * 81, baseUrl: s.url()] }
        "no address"           | { s -> [name: "Buchhaltung", baseUrl: ""] }
        "plain HTTP elsewhere" | { s -> [name: "Buchhaltung", baseUrl: "http://software.example.com/mcp"] }
        "an internal address"  | { s -> [name: "Buchhaltung", baseUrl: "https://10.1.2.3/mcp"] }
        "credentials in it"    | { s -> [name: "Buchhaltung", baseUrl: "https://user:pw@software.example.com/mcp"] }
        "a host in its path"   | { s -> [name: "Buchhaltung", baseUrl: s.url().replace("/mcp", "//software.example.com/mcp")] }
        "a key with blanks"    | { s -> [name: "Buchhaltung", baseUrl: s.url(), apiKey: "a key"] }
    }

    def "a name is unique within the tenant"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        connect(admin)

        expect:
        api.status(admin, "POST", "/tenant/admin/mcp-connections", [name: "buchhaltung", baseUrl: software.url()]) == HttpStatus.CONFLICT
    }

    def "another host drops the stored key unless a new one comes"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Buchhaltung", baseUrl: software.url(), apiKey: "software-key-123"]) as Map

        when:
        def samePath = api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}",
                [name: "Buchhaltung", baseUrl: software.url() + "/v2"]) as Map
        def otherHost = api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}",
                [name: "Buchhaltung", baseUrl: "http://127.0.0.1:1/mcp"]) as Map

        then:
        samePath.keyHint == "…-123"
        otherHost.keyHint == null
    }

    def "another address is another software: its tools are read anew, all off - the old choices do not pass on"() {
        given: "the reading find_invoice turned on, and marked reading"
        def admin = api.tenantAdminToken(api.tenant().id)
        def connection = connect(admin)
        def base = "/tenant/admin/mcp-connections/${connection.id}"
        api.call(admin, "PUT", "$base/tools/${tool(connection, "find_invoice").id}", [enabled: true])

        and: "another software whose find_invoice changes something"
        def other = new FakeMcpSoftware()
        other.tools = [[name: "find_invoice", description: "Finds and books an invoice",
                        inputSchema: [type: "object", properties: [number: [type: "string"]]]]]

        when:
        def moved = api.call(admin, "PUT", base, [name: "Buchhaltung", baseUrl: other.url()]) as Map

        then:
        (moved.tools as List<Map>)*.name == ["find_invoice"]
        !tool(moved, "find_invoice").enabled
        tool(moved, "find_invoice").changes
        moved.toolsLoadedAt != null

        when: "only the name changes"
        api.call(admin, "PUT", "$base/tools/${tool(moved, "find_invoice").id}", [enabled: true])
        def renamed = api.call(admin, "PUT", base, [name: "Buchhaltung Neu", baseUrl: other.url()]) as Map

        then: "the choices stay"
        tool(renamed, "find_invoice").enabled

        cleanup:
        other?.stop()
    }

    def "the access list shows each group's level, set with the group; only the tenant's groups"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def group = api.group(admin, [])
        def otherGroup = api.group(admin, [])
        def foreignAdmin = api.tenantAdminToken(api.tenant().id)
        def foreignGroup = api.group(foreignAdmin, [])
        def connection = connect(admin)
        def path = "/tenant/admin/mcp-connections/${connection.id}/access"

        when:
        api.setLevel(admin, group.id, connection.id, "READ")
        def levels = api.call(admin, "GET", path) as List<Map>

        then:
        levels.find { it.groupId == group.id } == [groupId: group.id, name: group.name, wamGroup: null, level: "READ"]
        levels.find { it.groupId == otherGroup.id }.level == null
        !levels.any { it.groupId == foreignGroup.id }

        when:
        api.setLevel(admin, group.id, connection.id, null)

        then:
        (api.call(admin, "GET", path) as List<Map>).find { it.groupId == group.id }.level == null

        // A page still open from before is logged out by the 403 and loads the new frontend.
        and: "the routes that set a level at the connection - of 1.5.0 and of before - are refused like every unknown route"
        api.status(admin, "PUT", "$path/groups/${group.id}", [level: "WRITE"]) == HttpStatus.FORBIDDEN
        api.status(admin, "PUT", "$path/${group.id}", [level: "WRITE"]) == HttpStatus.FORBIDDEN
        (api.call(admin, "GET", path) as List<Map>).find { it.groupId == group.id }.level == null
    }

    def "a user with a level uses a turned-on reading tool in the chat; without a level or turned off, nothing"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = chatModel(tenant, admin)
        def user = api.user(tenant.id)
        def token = user.token as String
        def connection = connect(admin)
        def find = tool(connection, "find_invoice")
        def cancel = tool(connection, "cancel_invoice")
        def question = 'Ist Rechnung 4711 bezahlt? [[call Buchhaltung__find_invoice {"number":"4711"}]]'

        when: "turned on, but the user has no level"
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${find.id}", [enabled: true])
        api.ask(token, [modelId: chat.id, message: question])

        then:
        !fake.chats.last().tools.contains("Buchhaltung__find_invoice")

        when: "with a level"
        api.grant(admin, connection.id, user.id, "READ")
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${cancel.id}", [enabled: true])
        def answer = api.ask(token, [modelId: chat.id, message: question]).answer as Map

        then: "the reading tool is offered and called; the changing one only with WRITE"
        answer.content.contains("(Software: [Buchhaltung] Invoice 4711: 412,50 EUR, paid)")
        fake.chats.last().tools.contains("Buchhaltung__find_invoice")
        // Its schema as the Python SDK writes it ("title", "$schema") - the model still gets its parameters.
        offeredParameters("Buchhaltung__find_invoice").properties().keySet() == ["number"] as Set
        offeredParameters("Buchhaltung__find_invoice").required() == ["number"]
        !fake.chats.last().tools.contains("Buchhaltung__cancel_invoice")
        software.calls()*.params*.name == ["find_invoice"]

        when: "turned off again"
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${find.id}", [enabled: false])
        api.ask(token, [modelId: chat.id, message: question])

        then:
        !fake.chats.last().tools.contains("Buchhaltung__find_invoice")
        software.calls().size() == 1
    }

    def "whether a connection passes on to MCP clients is set per connection: off by default, a value required; another tenant's is 404"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def connection = connect(admin)
        def path = "/tenant/admin/mcp-connections/${connection.id}/pass-through"

        expect:
        connection.passesThrough == false
        (api.call(admin, "PUT", path, [passesThrough: true]) as Map).passesThrough == true
        (api.admin("GET", "/admin/tenants/${tenant.id}/mcp-connections") as List<Map>)[0].passesThrough == true
        api.error(admin, "PUT", path, [:]) == "Whether the connection is passed on to MCP clients is required"
        (api.admin("PUT", "/admin/tenants/${tenant.id}/mcp-connections/${connection.id}/pass-through",
                [passesThrough: false]) as Map).passesThrough == false
        api.status(api.tenantAdminToken(api.tenant().id), "PUT", path, [passesThrough: true]) == HttpStatus.NOT_FOUND
        !(api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>)[0].containsKey("mcpServerAccess")
    }

    def "an API key chooses its software: none by default, on create and change; another tenant's connection is 400"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def connection = connect(admin)
        def foreign = connect(api.tenantAdminToken(api.tenant().id))

        when:
        def plain = (api.call(admin, "POST", "/tenant/admin/api-keys", [name: "Website"]) as Map).key as Map
        def given = (api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "ERP", connectionIds: [connection.id]]) as Map).key as Map

        then:
        plain.connectionIds == []
        given.connectionIds == [connection.id]
        (api.call(admin, "GET", "/tenant/admin/api-keys") as List<Map>).find { it.name == "ERP" }.connectionIds == [connection.id]
        (api.call(admin, "PUT", "/tenant/admin/api-keys/${plain.id}", [connectionIds: [connection.id]]) as Map).connectionIds == [connection.id]
        (api.call(admin, "PUT", "/tenant/admin/api-keys/${plain.id}", [name: "Shop"]) as Map).connectionIds == [connection.id]
        api.error(admin, "POST", "/tenant/admin/api-keys", [name: "X", connectionIds: [foreign.id]]) == "Unknown connected software"
        api.error(admin, "PUT", "/tenant/admin/api-keys/${plain.id}", [connectionIds: [foreign.id]]) == "Unknown connected software"
        !(api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>)[0].containsKey("clientSoftwareAccess")
        api.status(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/client-software", [level: "READ"]) !=
                HttpStatus.OK

        when: "the connection deleted"
        api.status(admin, "DELETE", "/tenant/admin/mcp-connections/${connection.id}")

        then: "it leaves every key"
        (api.call(admin, "GET", "/tenant/admin/api-keys") as List<Map>)*.connectionIds == [[], []]
    }

    def "the client software reaches the software's reading tools only with a key given the software (#key)"() {
        given: "the reading and the changing tool turned on"
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = chatModel(tenant, admin)
        def connection = connect(admin)
        def base = "/tenant/admin/mcp-connections/${connection.id}"
        (connection.tools as List<Map>).each { api.call(admin, "PUT", "$base/tools/${it.id}", [enabled: true]) }
        def created = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Website", keepsHistory: keepsHistory]) as Map
        def apiKey = created.plainKey as String
        def keyPath = "/tenant/admin/api-keys/${(created.key as Map).id}"
        def otherKey = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Shop", keepsHistory: keepsHistory,
                                                                                    connectionIds: []]).plainKey as String
        def question = [modelId: chat.id, message: 'Storniere Rechnung 4711. [[call Buchhaltung__cancel_invoice {"number":"4711"}]] ' +
                '[[call Buchhaltung__find_invoice {"number":"4711"}]]']

        when: "closed by default - passed on to MCP clients alone does not count"
        api.call(admin, "PUT", "$base/pass-through", [passesThrough: true])
        api.call(apiKey, "POST", "/api/v1/chat", question)

        then:
        !fake.chats.last().tools.any { it.contains("__") }
        software.calls().isEmpty()

        when: "a tenant admin gives the key the software"
        api.call(admin, "PUT", keyPath, [connectionIds: [connection.id]])
        def answer = api.call(apiKey, "POST", "/api/v1/chat", question) as Map

        then: "its reading tool is offered and called - never the changing one"
        fake.chats.last().tools.contains("Buchhaltung__find_invoice")
        !fake.chats.last().tools.contains("Buchhaltung__cancel_invoice")
        (answer.answer as Map).content.contains("(Software: [Buchhaltung] Invoice 4711: 412,50 EUR, paid)")
        software.calls()*.params*.name == ["find_invoice"]

        when: "another key of the tenant asks"
        api.call(otherKey, "POST", "/api/v1/chat", question)

        then:
        !fake.chats.last().tools.any { it.contains("__") }
        software.calls().size() == 1

        when: "taken off the key again"
        api.call(admin, "PUT", keyPath, [connectionIds: []])
        api.call(apiKey, "POST", "/api/v1/chat", question)

        then:
        !fake.chats.last().tools.any { it.contains("__") }
        software.calls().size() == 1

        where:
        key                   | keepsHistory
        "key without history" | false
        "key with history"    | true
    }

    def "another tenant's client software never reaches a connection given to this tenant's keys"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def connection = connect(admin)
        def base = "/tenant/admin/mcp-connections/${connection.id}"
        api.call(admin, "PUT", "$base/tools/${tool(connection, "find_invoice").id}", [enabled: true])
        api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Website", connectionIds: [connection.id]])
        def other = api.tenant()
        def otherChat = chatModel(other, api.tenantAdminToken(other.id))
        def otherKey = api.admin("POST", "/admin/tenants/${other.id}/api-keys", [name: "Website", keepsHistory: false]).plainKey as String

        when:
        api.call(otherKey, "POST", "/api/v1/chat",
                [modelId: otherChat.id, message: 'Ist Rechnung 4711 bezahlt? [[call Buchhaltung__find_invoice {"number":"4711"}]]'])

        then:
        !fake.chats.last().tools.any { it.contains("__") }
        software.calls().isEmpty()
    }

    def "with WRITE a changing call stops the answer and waits for the user; run, it goes to the software and the model goes on"() {
        given:
        def setup = writeSetup()

        when: "the model calls the changing tool"
        def asked = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION])
        def action = asked.answer.action as Map

        then: "the answer stops there - nothing ran at the software"
        action.status == "PENDING"
        action.software == "Buchhaltung"
        action.tool == "cancel_invoice"
        action.arguments == [number: "4711"]
        software.calls().isEmpty()

        and: "the conversation shows the waiting call with its answer"
        def conversation = asked.conversation.id
        (messages(setup.token, conversation).last().action as Map).status == "PENDING"

        when:
        def done = api.decide(setup.token, action.id, "run")

        then: "the software ran it once, and the model went on with its answer - stored as the next answer"
        software.calls()*.params == [[name: "cancel_invoice", arguments: [number: "4711"]]]
        done.question == null
        done.answer.content.contains("The software answered:\nInvoice 4711 cancelled")

        and: "it heard of the decision in a message of its own - the call without its thought signature would be refused"
        !fake.chats.last().messages.any { it instanceof dev.langchain4j.data.message.ToolExecutionResultMessage }
        def stored = messages(setup.token, conversation)
        stored*.role == ["USER", "ASSISTANT", "ASSISTANT"]
        (stored[1].action as Map).status == "RUN"
        (stored[1].action as Map).result == "Invoice 4711 cancelled"

        and: "a call is decided once"
        api.status(setup.token, "POST", "/tenant/chat/actions/${action.id}/run") == HttpStatus.CONFLICT
        api.status(setup.token, "POST", "/tenant/chat/actions/${action.id}/cancel") == HttpStatus.CONFLICT
        software.calls().size() == 1

        when: "the user asks on - the stopped answer had no text of its own"
        def next = api.ask(setup.token, [conversationId: conversation, modelId: setup.chat.id, message: "Hat es geklappt?"])

        then: "the history says what became of the call - an empty answer there is refused by Gemini and Claude"
        next.answer.content.startsWith("Antwort auf: Hat es geklappt?")
        fake.chats.last().messages.any {
            it instanceof dev.langchain4j.data.message.AiMessage && it.text()?.contains(
                    '[Stopped at the call Buchhaltung cancel_invoice {"number":"4711"} - run by the user\'s decision, result: Invoice 4711 cancelled]')
        }
        software.calls().size() == 1
    }

    def "a response calling a reading and a changing tool together runs neither - the changing one waits for the user"() {
        given:
        def setup = writeSetup()
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/tools/${tool(setup.connection, "find_invoice").id}",
                [enabled: true])

        when:
        def asked = api.ask(setup.token, [modelId: setup.chat.id, message: "Prüfe und storniere 4711. [[together]] "
                + '[[call Buchhaltung__find_invoice {"number":"4711"}]] [[call Buchhaltung__cancel_invoice {"number":"4711"}]]'])

        then:
        (asked.answer.action as Map).status == "PENDING"
        (asked.answer.action as Map).tool == "cancel_invoice"
        software.calls().isEmpty()
    }

    def "cancelled, the software is never asked and the model hears that the user declined"() {
        given:
        def setup = writeSetup()
        def action = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION]).answer.action as Map

        when:
        def done = api.decide(setup.token, action.id, "cancel")

        then:
        software.calls().isEmpty()
        done.answer.content.contains("The user declined the call you stopped at - it was not run.")
        (messages(setup.token, done.conversation.id)[1].action as Map).status == "CANCELLED"
    }

    def "going on after a decision, the model searches the user's knowledge only - never an area outside its groups"() {
        given:
        def setup = writeSetup()
        def embedding = api.embeddingModel()
        api.allow(setup.tenant.id, embedding)
        api.call(setup.admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        api.call(setup.admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        def workshop = api.call(setup.admin, "POST", "/tenant/admin/knowledge-areas", [name: "Werkstatt"]) as Map
        // The fake searches with the decision's message as its question - both documents match it.
        String decided = "The user declined the call you stopped at - it was not run. Say so; do not claim it was done."
        api.awaitDocument(setup.admin, api.upload(setup.admin, "allgemein.txt", decided + " Allgemein").id)
        def workshopDocument = api.upload(setup.admin, "werkstatt.txt", decided + " Werkstatt").id
        api.awaitDocument(setup.admin, workshopDocument)
        api.call(setup.admin, "PUT", "/tenant/admin/documents/${workshopDocument}/area", [areaId: workshop.id])
        def action = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION]).answer.action as Map

        when:
        def done = api.decide(setup.token, action.id, "cancel")

        then: "the answer going on found the company knowledge, not the area the user's groups do not hold"
        (done.sources as List<String>) == ["allgemein.txt"]
    }

    def "the software out of reach is a text for the model - when it reads, and when a decided call runs"() {
        given:
        def setup = writeSetup()
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/tools/${tool(setup.connection, "find_invoice").id}",
                [enabled: true])
        def action = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION]).answer.action as Map
        software.stop()

        when: "a reading call"
        def read = api.ask(setup.token, [modelId: setup.chat.id,
                                         message: 'Ist 4711 bezahlt? [[call Buchhaltung__find_invoice {"number":"4711"}]]'])

        then: "the answer goes on with the failure"
        read.answer.content.contains("Buchhaltung is not reachable")

        when: "the waiting call is run"
        def done = api.decide(setup.token, action.id, "run")

        then: "it counts as run - the change may have happened - and the model hears the failure"
        done.answer.content.contains("Buchhaltung is not reachable")
        def stopped = messages(setup.token, done.conversation.id)[1].action as Map
        stopped.status == "RUN"
        stopped.result.contains("Buchhaltung is not reachable")
    }

    def "a key of the connection no longer readable is a text for the model, and a reload says to enter it again"() {
        given: "the connection's key stored so that it cannot be decrypted any more (APP_SECRET_KEY changed)"
        def setup = readSetup()
        managementDatabase.connection.withCloseable {
            it.prepareStatement("UPDATE mcp_connection SET api_key = 'broken', key_hint = '…oken' WHERE id = ${setup.connection.id}").executeUpdate()
        }
        def unreadable = "Buchhaltung: our stored key can no longer be read - enter it again in the connection"

        when:
        def read = api.ask(setup.token, [modelId: setup.chat.id,
                                         message: 'Ist 4711 bezahlt? [[call Buchhaltung__find_invoice {"number":"4711"}]]'])
        def reloaded = api.call(setup.admin, "POST", "/tenant/admin/mcp-connections/${setup.connection.id}/reload") as Map

        then: "the answer goes on with the refusal; the reload keeps the tools and names it"
        read.answer.content.contains(unreadable)
        software.calls().isEmpty()
        reloaded.toolsError == unreadable
        (reloaded.tools as List<Map>)*.name == ["cancel_invoice", "find_invoice"]
    }

    def "going on after a decision, the model gets the conversation's files listed like with a question"() {
        given:
        def setup = writeSetup()
        def asked = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION])
        api.chatUpload(setup.token, ["notiz.txt": "Rechnung 4711 ist doppelt".bytes], asked.conversation.id)

        when:
        api.decide(setup.token, asked.answer.action.id, "cancel")

        then:
        fake.chats.any { chat ->
            chat.messages.any {
                it instanceof dev.langchain4j.data.message.UserMessage && it.singleText().contains('"notiz.txt"')
                        && it.singleText().contains("The user declined the call you stopped at")
            }
        }
    }

    def "the provider failing after a run keeps the call run with its result; the next question goes on"() {
        given:
        def setup = writeSetup()
        def asked = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION])
        def conversation = asked.conversation.id
        fake.failures = 1

        when:
        def events = api.events(setup.token, "/tenant/chat/actions/${asked.answer.action.id}/run", [:])

        then: "the change was made - the user sees the error, and the call shows it ran"
        events.last().event == "error"
        software.calls().size() == 1
        def stored = messages(setup.token, conversation)
        stored*.role == ["USER", "ASSISTANT"]
        (stored[1].action as Map).status == "RUN"
        (stored[1].action as Map).result == "Invoice 4711 cancelled"

        when:
        def next = api.ask(setup.token, [conversationId: conversation, modelId: setup.chat.id, message: "Hat es geklappt?"])

        then:
        next.answer.content.startsWith("Antwort auf: Hat es geklappt?")
        software.calls().size() == 1
    }

    def "the provider failing after a run keeps the file the software returned - at the answer that stopped"() {
        given:
        def setup = writeSetup()
        software.callContent = [[type: "text", text: "Invoice 4711 cancelled"],
                                FakeMcpSoftware.blob("file:///storno-4711.pdf", "application/pdf", "%PDF-1.4".getBytes("UTF-8"))]
        def asked = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION])
        fake.failures = 1

        when:
        def events = api.events(setup.token, "/tenant/chat/actions/${asked.answer.action.id}/run", [:])

        then: "the call cannot run again, so its file stays where the user decided it"
        events.last().event == "error"
        def stopped = messages(setup.token, asked.conversation.id)[1]
        (stopped.action as Map).status == "RUN"
        (stopped.files as List<Map>)*.fileName == ["storno-4711.pdf"]
        api.download(setup.token, "/tenant/chat/files/${(stopped.files as List<Map>)[0].id}/content").body() ==
                "%PDF-1.4".getBytes("UTF-8")
    }

    def "a template deleted meanwhile does not block the decision - the tenant's settings answer"() {
        given:
        def setup = writeSetup()
        def template = api.call(setup.admin, "POST", "/tenant/admin/assistant/templates", [name: "Buchhaltung"]) as Map
        def action = api.ask(setup.token, [modelId: setup.chat.id, templateId: template.id, message: CANCEL_QUESTION])
                .answer.action as Map
        api.status(setup.admin, "DELETE", "/tenant/admin/assistant/templates/${template.id}") == HttpStatus.NO_CONTENT

        when:
        def done = api.decide(setup.token, action.id, "cancel")

        then:
        done.answer.content.contains("The user declined the call you stopped at")
        software.calls().isEmpty()
    }

    def "only the user who asked decides - another user's call is 404; a level taken back meanwhile is 409"() {
        given:
        def setup = writeSetup()
        def action = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION]).answer.action as Map
        def colleague = api.userToken(setup.tenant.id)
        def stranger = api.userToken(api.tenant().id)

        expect:
        api.status(colleague, "POST", "/tenant/chat/actions/${action.id}/run") == HttpStatus.NOT_FOUND
        api.status(stranger, "POST", "/tenant/chat/actions/${action.id}/run") == HttpStatus.NOT_FOUND
        api.status(setup.token, "POST", "/tenant/chat/actions/999999/run") == HttpStatus.NOT_FOUND

        when: "the tenant admin gives the user's group READ only"
        api.setLevel(setup.admin, setup.groupId, setup.connection.id, "READ")

        then:
        api.error(setup.token, "POST", "/tenant/chat/actions/${action.id}/run") == "This tool is no longer turned on for you"
        software.calls().isEmpty()

        and: "with READ the changing tool is not offered at all"
        api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION]).answer.action == null
        !fake.chats.last().tools.contains("Buchhaltung__cancel_invoice")
    }

    def "a waiting call expires with a new question in its conversation, and after a day"() {
        given:
        def setup = writeSetup()
        def first = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION])
        def conversation = first.conversation.id

        when: "a new question comes"
        def second = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION, conversationId: conversation])

        then:
        api.error(setup.token, "POST", "/tenant/chat/actions/${first.answer.action.id}/run") == "This action expired - ask again"
        (messages(setup.token, conversation)[1].action as Map).status == "EXPIRED"

        when: "a day passes"
        ageActions(setup.tenant.id, Duration.ofHours(25))

        then:
        (messages(setup.token, conversation).last().action as Map).status == "EXPIRED"
        api.error(setup.token, "POST", "/tenant/chat/actions/${second.answer.action.id}/cancel") == "This action expired - ask again"
        software.calls().isEmpty()
    }

    def "a waiting call expires when the new question comes, even when its answer fails"() {
        given:
        def setup = writeSetup()
        def first = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION])
        fake.failures = 1

        when:
        def events = api.askEvents(setup.token, [modelId: setup.chat.id, message: "Noch etwas",
                                                 conversationId: first.conversation.id])

        then:
        events.collect { it.event } == ["error"]
        api.error(setup.token, "POST", "/tenant/chat/actions/${first.answer.action.id}/run") == "This action expired - ask again"
        software.calls().isEmpty()
    }

    def "a stopped answer stored after a newer question keeps its call expired - the newer question's call waits on"() {
        given: "a question the user stopped listening to: prepared, its answer not yet there"
        def setup = writeSetup()
        def conversation = api.ask(setup.token, [modelId: setup.chat.id, message: "Hallo"]).conversation.id
        def stopped = chatService.prepare(setup.tenant.id as Long, setup.user.id as Long, conversation as Long,
                setup.chat.id as Long, null, CANCEL_QUESTION, null)

        and: "a newer question in the conversation, stopping at a call of its own"
        def newer = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION, conversationId: conversation])

        when: "the stopped answer ends at its changing call and is stored"
        def ended = new CompletableFuture<Object>()
        chatService.stream(stopped, {}, {}, {}, {}, { ended.complete(it) }, { ended.complete(it) })
        def late = (ended.get(10, TimeUnit.SECONDS) as ChatResult).action()

        then: "its call never waits"
        late.status().name() == "EXPIRED"
        api.error(setup.token, "POST", "/tenant/chat/actions/${late.id()}/run") == "This action expired - ask again"

        and: "the newer question's call is untouched and runs"
        api.decide(setup.token, newer.answer.action.id, "run").answer.content.contains("Invoice 4711 cancelled")
        software.calls().size() == 1
        messages(setup.token, conversation).collect { (it.action as Map)?.status }.findAll() == ["RUN", "EXPIRED"]
    }

    def "another tenant's connections, tools and access answer 404; deleting removes the connection"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def other = api.tenantAdminToken(api.tenant().id)
        def connection = connect(admin)
        def find = tool(connection, "find_invoice")
        def base = "/tenant/admin/mcp-connections/${connection.id}"
        def ownGroup = api.group(admin, [])
        def otherGroup = api.group(other, [])

        expect:
        api.call(other, "GET", "/tenant/admin/mcp-connections") == []
        api.status(other, "PUT", base, [name: "X", baseUrl: software.url()]) == HttpStatus.NOT_FOUND
        api.status(other, "POST", "$base/reload") == HttpStatus.NOT_FOUND
        api.status(other, "PUT", "$base/tools/${find.id}", [enabled: true]) == HttpStatus.NOT_FOUND
        api.status(other, "GET", "$base/access") == HttpStatus.NOT_FOUND
        api.status(other, "PUT", "/tenant/admin/groups/${otherGroup.id}",
                [name: otherGroup.name, levels: [[connectionId: connection.id, level: "READ"]]]) == HttpStatus.BAD_REQUEST
        api.status(other, "PUT", "/tenant/admin/groups/${ownGroup.id}",
                [name: ownGroup.name, levels: [[connectionId: connection.id, level: "READ"]]]) == HttpStatus.NOT_FOUND
        // The level is set with the group only (roadmap point 43) - the connection's access list just shows it.
        api.status(admin, "PUT", "$base/access/groups/${ownGroup.id}", [level: "READ"]) != HttpStatus.OK
        api.status(other, "DELETE", base) == HttpStatus.NOT_FOUND
        api.status(api.adminToken, "GET", "/admin/tenants/999999/mcp-connections") == HttpStatus.NOT_FOUND

        and:
        api.status(admin, "DELETE", base) == HttpStatus.NO_CONTENT
        api.call(admin, "GET", "/tenant/admin/mcp-connections") == []
    }

    def "a tool of another tenant's connection is 404 - also through the tenant's own connection"() {
        given:
        def owner = api.tenantAdminToken(api.tenant().id)
        def other = api.tenantAdminToken(api.tenant().id)
        def foreignTool = tool(connect(owner), "find_invoice")
        def ownConnection = connect(other)

        expect:
        api.status(other, "PUT", "/tenant/admin/mcp-connections/${ownConnection.id}/tools/${foreignTool.id}", [enabled: true]) ==
                HttpStatus.NOT_FOUND
        !tool(api.call(owner, "GET", "/tenant/admin/mcp-connections")[0] as Map, "find_invoice").enabled
    }

    def "the software's prompts are read with its tools - all off; a tenant admin turns one on"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)

        when:
        def connection = connect(admin)
        def prompt = (connection.prompts as List<Map>)[0]

        then:
        (connection.prompts as List<Map>).collect { [it.name, it.title, it.enabled, it.offered] } ==
                [["create_test_program", "Prüfprogramm erstellen", false, true]]
        prompt.arguments*.name == ["package_name", "project_name", "comment"]
        prompt.arguments*.required == [true, true, false]
        connection.toolsError == null

        when:
        def turnedOn = api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/prompts/${prompt.id}", [enabled: true]) as Map

        then: "the admin sees it too, and another tenant's admin cannot touch it"
        turnedOn.enabled
        ((api.admin("GET", "/admin/tenants/${tenant.id}/mcp-connections") as List<Map>)[0].prompts as List<Map>)[0].enabled
        api.status(api.tenantAdminToken(api.tenant().id), "PUT",
                "/tenant/admin/mcp-connections/${connection.id}/prompts/${prompt.id}", [enabled: false]) == HttpStatus.NOT_FOUND
    }

    def "a server that stops offering its prompts - or one prompt - is no error: the dropped ones are no longer offered"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def connection = connect(admin)

        when:
        software.prompts = given
        def reloaded = api.call(admin, "POST", "/tenant/admin/mcp-connections/${connection.id}/reload") as Map

        then:
        reloaded.toolsError == null
        (reloaded.prompts as List<Map>)*.offered == [false]

        where:
        given << [null, []]
    }

    def "prompts that cannot be read are named apart - the tools are read all the same"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        software.prompts = [[name: "p" * 200]]

        when:
        def connection = connect(admin)

        then:
        connection.toolsError == null
        connection.promptsError == "Buchhaltung offers a prompt with a name longer than 128 characters"
        (connection.tools as List<Map>).size() == 2
        connection.prompts == []
    }

    def "a prompt whose connection is gone meanwhile is 404, not an error of the server"() {
        given:
        def setup = promptSetup()
        def id = (api.call(setup.token, "GET", "/tenant/chat/prompts") as List<Map>)[0].id

        expect:
        api.status(setup.admin, "DELETE", "/tenant/admin/mcp-connections/${setup.connection.id}") == HttpStatus.NO_CONTENT
        api.status(setup.token, "POST", "/tenant/chat/prompts/$id",
                [arguments: [package_name: "com.example.demo", project_name: "demo"]]) == HttpStatus.NOT_FOUND
    }

    def "a user with a level uses a turned-on prompt: its form's values become the text for the input field"() {
        given:
        def setup = promptSetup()

        when: "listed with the software's name and the arguments"
        def prompts = api.call(setup.token, "GET", "/tenant/chat/prompts") as List<Map>

        then:
        prompts.collect { [it.software, it.name, it.title] } == [["Buchhaltung", "create_test_program", "Prüfprogramm erstellen"]]
        prompts[0].arguments*.name == ["package_name", "project_name", "comment"]

        when:
        def filled = api.call(setup.token, "POST", "/tenant/chat/prompts/${prompts[0].id}",
                [arguments: [package_name: "com.example.demo", project_name: "demo-project", unknown: "x"]]) as Map

        then: "the user messages' text - not the assistant's, not the image; an argument the prompt has not is not sent"
        filled.text == "Erstelle ein Prüfprogramm für com.example.demo im Projekt demo-project.\n\nAnmerkung: keine"
        (software.received.find { it.method == "prompts/get" }.params as Map) ==
                [name: "create_test_program", arguments: [package_name: "com.example.demo", project_name: "demo-project"]]
        fake.chats.isEmpty()
    }

    def "a prompt's values are checked: #reason"() {
        given:
        def setup = promptSetup()
        def id = (api.call(setup.token, "GET", "/tenant/chat/prompts") as List<Map>)[0].id

        expect:
        api.status(setup.token, "POST", "/tenant/chat/prompts/$id", [arguments: arguments]) == HttpStatus.BAD_REQUEST
        api.error(setup.token, "POST", "/tenant/chat/prompts/$id", [arguments: arguments]) == message

        where:
        reason               | arguments                                                        || message
        "a required missing" | [package_name: "com.example.demo"]                              || 'The value "project_name" is required'
        "a required blank"   | [package_name: " ", project_name: "demo"]                      || 'The value "package_name" is required'
        "a value too long"   | [package_name: "a" * 10_001, project_name: "demo"]             || "A value may have at most 10000 characters"
    }

    def "only a user with a level sees a prompt, only a turned-on one; another tenant's is 404; the software's error is 502"() {
        given:
        def setup = promptSetup()
        def id = (api.call(setup.token, "GET", "/tenant/chat/prompts") as List<Map>)[0].id
        def withoutLevel = api.user(setup.tenant.id)
        def values = [arguments: [package_name: "com.example.demo", project_name: "demo"]]

        expect: "no level: nothing"
        api.call(withoutLevel.token as String, "GET", "/tenant/chat/prompts") == []
        api.status(withoutLevel.token as String, "POST", "/tenant/chat/prompts/$id", values) == HttpStatus.NOT_FOUND

        and: "another tenant's user: nothing"
        def other = api.user(api.tenant().id)
        api.status(other.token as String, "POST", "/tenant/chat/prompts/$id", values) == HttpStatus.NOT_FOUND

        when: "the software fails"
        software.promptError = "boom"

        then:
        api.status(setup.token, "POST", "/tenant/chat/prompts/$id", values) == HttpStatus.BAD_GATEWAY

        when: "turned off"
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/prompts/$id", [enabled: false])

        then:
        api.call(setup.token, "GET", "/tenant/chat/prompts") == []
        api.status(setup.token, "POST", "/tenant/chat/prompts/$id", values) == HttpStatus.NOT_FOUND
    }

    /** A tenant whose user has READ for the connection through a group, its prompt turned on. */
    private Map promptSetup() {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def user = api.user(tenant.id)
        def connection = connect(admin)
        def prompt = (connection.prompts as List<Map>)[0]
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/prompts/${prompt.id}", [enabled: true])
        api.grant(admin, connection.id, user.id, "READ")
        [tenant: tenant, admin: admin, user: user, token: user.token as String, connection: connection]
    }

    static final String FIND_QUESTION = 'Erstelle das Paket. [[call Buchhaltung__find_invoice {"number":"4711"}]]'

    def "a file the software's tool returns is offered below the answer and can be downloaded"() {
        given:
        def setup = readSetup()
        def zip = FakeMcpSoftware.zip("src/Main.java", "class Main {}")
        software.callContent = [[type: "text", text: "Test program generated"],
                                FakeMcpSoftware.blob("file:///demo-project.zip", "application/zip", zip)]

        when:
        def result = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION])

        then: "the model hears of the file and is told not to link it"
        def answer = result.answer as Map
        answer.content.contains('Test program generated\nThe file "demo-project.zip" was created. The application shows '
                + 'its download below the answer - do not write a link or path to it.')

        and: "the file hangs at the answer like one create_file made"
        (answer.files as List<Map>).collect { [it.fileName, it.contentType, it.origin, it.sizeBytes] } ==
                [["demo-project.zip", "application/zip", "GENERATED", zip.length]]

        when:
        def download = api.download(setup.token, "/tenant/chat/files/${answer.files[0].id}/content")

        then:
        download.status == HttpStatus.OK
        download.body() == zip
        download.header("Content-Disposition").startsWith("attachment")

        when: "the model reads it"
        def read = api.ask(setup.token, [conversationId: result.conversation.id, modelId: setup.chat.id,
                                         message: "Was ist in dem Paket?"]).answer as Map

        then: "it is listed as created by the model - an archive has no text it could read"
        read.content.contains('(Error: "demo-project.zip" has no text you can read - it is for the user to download)')
        fake.chats.last().messages.any {
            it instanceof dev.langchain4j.data.message.UserMessage && it.singleText().contains(
                    "\"demo-project.zip\" (application/zip, ${zip.length} bytes, created by you)")
        }
    }

    def "the model gets a result's structured content beside its text - once, where the text is that JSON already: #reason"() {
        given:
        def setup = readSetup()
        software.callContent = [[type: "text", text: text]]
        software.callStructured = [invoice: "4711", total: 412.5]

        when:
        def answer = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION]).answer as Map

        then:
        answer.content.contains(expected)
        (answer.content as String).count('"invoice"') == 1

        where:
        reason               | text                                 || expected
        "a summary as text"  | "1 invoice found"                    || '1 invoice found\n\n=== Structured result ===\n{"invoice":"4711","total":412.5}'
        "the JSON as text"   | '{"invoice": "4711", "total": 412.5}' || '{"invoice": "4711", "total": 412.5}'
    }

    def "the model reads a returned text file in the tool's result at once - not an archive, not a Latin-1 file: #reason"() {
        given:
        def setup = readSetup()
        software.callContent = [[type: "text", text: "Generated"], FakeMcpSoftware.blob(uri, mimeType, content)]

        when:
        def answer = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION]).answer as Map

        then:
        answer.content.contains('=== File "Main.java" (returned by the tool) ===\nclass Main {}') == shown
        (answer.files as List<Map>)*.fileName == [fileName]

        where:
        reason                 | uri                      | mimeType           | content                                      || shown | fileName
        "a text resource"      | "file:///Main.java"      | "text/x-java"      | "class Main {}".getBytes("UTF-8")            || true  | "Main.java"
        "an archive"           | "file:///project.zip"    | "application/zip"  | FakeMcpSoftware.zip("Main.java", "class Main {}") || false | "project.zip"
        "a Latin-1 file"       | "file:///Main.java"      | "text/x-java"      | "class Main {} // Größe".getBytes("ISO-8859-1") || false | "Main.java"
    }

    def "the returned text reaches the model also where no file is kept - and only as far as the result's limit"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = chatModel(tenant, admin)
        def connection = connect(admin)
        def base = "/tenant/admin/mcp-connections/${connection.id}"
        api.call(admin, "PUT", "$base/tools/${tool(connection, "find_invoice").id}", [enabled: true])
        def apiKey = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Website", connectionIds: [connection.id], keepsHistory: false]).plainKey as String
        software.callContent = [[type: "resource", resource: [uri: "file:///notes.md", mimeType: "text/markdown", text: "# Notiz"]],
                                [type: "resource", resource: [uri: "file:///big.log", mimeType: "text/plain", text: "x" * 150_000]]]

        when:
        def content = ((api.call(apiKey, "POST", "/api/v1/chat", [modelId: chat.id, message: FIND_QUESTION]) as Map).answer as Map).content as String

        then: "no file is kept for a key without history - the model still reads the text"
        content.contains('[file "notes.md" returned - files cannot be offered here]')
        content.contains('=== File "notes.md" (returned by the tool) ===\n# Notiz')

        and: "the long one is cut where the result's 100,000 characters end"
        content.contains('=== File "big.log" (returned by the tool) ===')
        content.contains("[cut off - the file is longer]")
        !content.contains("x" * 100_000)
    }

    def "an archive has no text for read_file - with the tenant's sandbox the model is sent to its code: #reason"() {
        given:
        def setup = readSetup()
        if (withSandbox) {
            api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name,
                    sandboxUrls: "http://alpdesk-ai-sandbox-${UUID.randomUUID().toString().substring(0, 8)}:8080".toString(),
                    sandboxToken: "t" * 40])
            sandboxes.up = true
        }
        software.callContent = [FakeMcpSoftware.blob("file:///demo-project.zip", "application/zip", FakeMcpSoftware.zip())]
        def result = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION])

        when:
        def read = api.ask(setup.token, [conversationId: result.conversation.id, modelId: setup.chat.id,
                                         message: "Was ist in dem Paket?"]).answer as Map

        then:
        read.content.contains('(Error: "demo-project.zip" has no text you can read - ' + hint + ')')

        where:
        reason         | withSandbox || hint
        "no sandbox"   | false       || "it is for the user to download"
        "with sandbox" | true        || "open it with run_shell or run_python: it is in data/ (an archive: unpack it there)"
    }

    def "a returned file's name and type are the application's: #reason"() {
        given:
        def setup = readSetup()
        def content = uri.endsWith("page.txt") ? "<html><body><p>Hallo</p></body></html>" : "content"
        software.callContent = [FakeMcpSoftware.blob(uri, mimeType, content.getBytes("UTF-8"))]

        when:
        def answer = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION]).answer as Map

        then:
        (answer.files as List<Map>).collect { [it.fileName, it.contentType] } == [[name, contentType]]

        where:
        reason                     | uri                          | mimeType                   || name        | contentType
        "a path in the name"       | "file:///../../evil.zip"     | "application/zip"          || "evil.zip"  | "application/zip"
        "HTML is no type it keeps" | "file:///page.html"          | "text/html"                || "page.html" | "application/octet-stream"
        "SVG neither"              | "file:///logo.svg"           | "image/svg+xml"            || "logo.svg"  | "application/octet-stream"
        "no type at all"           | "file:///data.bin"           | null                       || "data.bin"  | "application/octet-stream"
        "a type with parameters"   | "file:///list.csv"           | "text/csv; charset=utf-8"  || "list.csv"  | "text/csv"
        "no name: its extension"   | "file:///"                   | "application/x-tar"        || "datei.tar" | "application/x-tar"
        "the type its content shows" | "file:///bericht.pdf"      | "application/pdf"          || "bericht.pdf" | "text/plain"
        "a tar.gz"                 | "file:///build.tar.gz"       | "application/x-gzip"       || "build.tar.gz" | "application/x-gzip"
        "a 7z archive"             | "file:///export.7z"          | "application/x-7z-compressed" || "export.7z" | "application/x-7z-compressed"
        "a log"                    | "file:///server.log"         | "text/x-log"               || "server.log" | "text/x-log"
        "an image that is text"    | "file:///foto.png"           | "image/png"                || "foto.png"  | "text/plain"
        "HTML said to be text"     | "file:///page.txt"           | "text/plain"               || "page.txt"  | "application/octet-stream"
    }

    def "a returned image is stored like the image model's: shown below the answer, the model can look at it"() {
        given:
        def setup = readSetup()
        software.callContent = [content]

        when:
        def answer = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION]).answer as Map

        then:
        (answer.files as List<Map>).collect { [it.fileName, it.contentType, it.imageCount] } == [[name, "image/png", 1]]
        api.download(setup.token, "/tenant/chat/files/${answer.files[0].id}/content").body() == pngImage()

        where:
        kind                  | content                                                                      || name
        "an embedded resource" | FakeMcpSoftware.blob("file:///diagramm.png", "image/png", pngImage())       || "diagramm.png"
        "MCP's image content" | [type: "image", data: pngImage().encodeBase64().toString(), mimeType: "image/png"] || "bild.png"
    }

    def "a returned image the application cannot read is a plain download - no image announced to the model"() {
        given:
        def setup = readSetup()
        software.callContent = [FakeMcpSoftware.blob("file:///kaputt.png", "image/png", brokenPng())]

        when:
        def result = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION])
        def read = api.ask(setup.token, [conversationId: result.conversation.id, modelId: setup.chat.id,
                                         message: "Was zeigt das Bild?"]).answer as Map

        then:
        (result.answer.files as List<Map>).collect { [it.fileName, it.contentType, it.imageCount] } ==
                [["kaputt.png", "application/octet-stream", 0]]
        read.content.contains('"kaputt.png" has no text you can read')
    }

    private static byte[] brokenPng() {
        def bytes = new ByteArrayOutputStream()
        bytes.write(pngImage(), 0, 16)
        bytes.write("kaputt".getBytes("UTF-8"))
        bytes.toByteArray()
    }

    def "a returned document is read like an upload: the model reads a PDF's text, a scan's pages"() {
        given:
        def setup = readSetup()
        software.callContent = [FakeMcpSoftware.blob("file:///bericht.pdf", "application/pdf", pdf(("Umsatz 2026: 12 Prozent. " * 20).trim()))]

        when:
        def result = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION])
        def file = result.answer.files[0] as Map
        def dataSource = databases.dataSource(tenants.findById(setup.tenant.id as Long).get().databaseName())
        def read = api.ask(setup.token, [conversationId: result.conversation.id, modelId: setup.chat.id,
                                         message: "Was steht im Bericht?"]).answer as Map

        then: "its text was extracted, and the model reads it with read_file like an uploaded PDF's"
        file.contentType == "application/pdf"
        file.imageCount == 0
        fileRows.findText(dataSource, file.id as Long).get().contains("Umsatz 2026: 12 Prozent")
        read.content.contains("(Dateien: bericht.pdf)")
        !read.content.contains("Error:")
    }

    def "a document the software links to (a temporary download) is fetched at once and read like an upload"() {
        given:
        def setup = readSetup()
        software.downloads["bericht"] = [body: pdf(("Umsatz 2026: 12 Prozent. " * 20).trim()), type: "application/pdf"]
        software.callContent = [[type: "text", text: "Der Bericht"],
                                FakeMcpSoftware.link(software.fileUrl("bericht"), "bericht.pdf")]

        when:
        def result = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION])
        def file = result.answer.files[0] as Map
        def dataSource = databases.dataSource(tenants.findById(setup.tenant.id as Long).get().databaseName())

        then:
        file.fileName == "bericht.pdf"
        file.contentType == "application/pdf"
        fileRows.findText(dataSource, file.id as Long).get().contains("Umsatz 2026: 12 Prozent")
        software.downloadRequests*.path == ["bericht"]
    }

    def "a failed call's file is not offered - the model hears of it"() {
        given:
        def setup = readSetup()
        software.callContent = [[type: "text", text: "Generation failed"],
                                FakeMcpSoftware.blob("file:///error.log", "text/plain", "stack".getBytes("UTF-8"))]
        software.callIsError = true

        when:
        def answer = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION]).answer as Map

        then:
        answer.files == null || answer.files == []
        answer.content.contains('Generation failed\n[file "error.log" returned with the error - not offered]')
    }

    def "the model reads a returned file's text by its type or its name - not a binary file's: #reason"() {
        given:
        def setup = readSetup()
        software.callContent = [FakeMcpSoftware.blob(uri, mimeType, content)]

        when:
        def result = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION])
        def fileId = result.answer.files[0].id as Long
        def dataSource = databases.dataSource(tenants.findById(setup.tenant.id as Long).get().databaseName())

        then:
        fileRows.findText(dataSource, fileId).orElse(null) == text

        where:
        reason                 | uri                    | mimeType                   | content                               || text
        "a text type"          | "file:///notes.txt"    | "text/plain"               | "Hallo".getBytes("UTF-8")             || "Hallo"
        "YAML by its type"     | "file:///config"       | "application/x-yaml"       | "port: 80".getBytes("UTF-8")          || "port: 80"
        "a log by its name"    | "file:///server.log"   | "application/octet-stream" | "ERROR boot".getBytes("UTF-8")        || "ERROR boot"
        "code by its name"     | "file:///Main.java"    | null                       | "class Main {}".getBytes("UTF-8")     || "class Main {}"
        "a binary named .log"  | "file:///dump.log"     | "application/octet-stream" | [0xC3, 0x28, 0x00] as byte[]          || null
        "an archive"           | "file:///project.zip"  | "application/zip"          | FakeMcpSoftware.zip()                 || null
    }

    def "an answer keeps at most five files - the ones of create_file and the software's together"() {
        given:
        def setup = readSetup()
        software.callContent = (1..6).collect { FakeMcpSoftware.blob("file:///teil-${it}.zip", "application/zip", FakeMcpSoftware.zip()) }

        when:
        def answer = api.ask(setup.token, [modelId: setup.chat.id, message: FIND_QUESTION]).answer as Map

        then:
        (answer.files as List<Map>)*.fileName == (1..5).collect { "teil-${it}.zip" }
        answer.content.contains('[file "teil-6.zip" left out - at most 5 files per answer]')
    }

    def "the client software gets a returned file only with a key that keeps history (#key)"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = chatModel(tenant, admin)
        def connection = connect(admin)
        def base = "/tenant/admin/mcp-connections/${connection.id}"
        api.call(admin, "PUT", "$base/tools/${tool(connection, "find_invoice").id}", [enabled: true])
        def apiKey = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Website", connectionIds: [connection.id], keepsHistory: keepsHistory]).plainKey as String
        software.callContent = [FakeMcpSoftware.blob("file:///demo-project.zip", "application/zip", FakeMcpSoftware.zip())]

        when:
        def answer = (api.call(apiKey, "POST", "/api/v1/chat", [modelId: chat.id, message: FIND_QUESTION]) as Map).answer as Map

        then:
        ((answer.files ?: []) as List<Map>)*.fileName == files
        answer.content.contains(note)

        where:
        key                   | keepsHistory || files                | note
        "key without history" | false        || []                   | '[file "demo-project.zip" returned - files cannot be offered here]'
        "key with history"    | true         || ["demo-project.zip"] | 'The file "demo-project.zip" was created.'
    }

    def "a changing call run by the user's decision brings its file to the answer that goes on"() {
        given:
        def setup = writeSetup()
        software.callContent = [[type: "text", text: "Invoice 4711 cancelled"],
                                FakeMcpSoftware.blob("file:///storno-4711.pdf", "application/pdf", "%PDF-1.4".getBytes("UTF-8"))]
        def action = api.ask(setup.token, [modelId: setup.chat.id, message: CANCEL_QUESTION]).answer.action as Map

        when:
        def done = api.decide(setup.token, action.id, "run")

        then:
        (done.answer.files as List<Map>)*.fileName == ["storno-4711.pdf"]
    }

    private static byte[] pdf(String text) {
        def document = new org.apache.pdfbox.pdmodel.PDDocument()
        def page = new org.apache.pdfbox.pdmodel.PDPage()
        document.addPage(page)
        new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page).withCloseable { stream ->
            stream.beginText()
            stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12)
            stream.newLineAtOffset(50, 700)
            stream.showText(text)
            stream.endText()
        }
        def out = new ByteArrayOutputStream()
        document.save(out)
        document.close()
        out.toByteArray()
    }

    static byte[] pngImage() {
        def image = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
        def bytes = new ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", bytes)
        bytes.toByteArray()
    }

    /** A tenant whose user may read: its find_invoice turned on, the user with READ through its group (groupId). */
    private Map readSetup() {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = chatModel(tenant, admin)
        def user = api.user(tenant.id)
        def connection = connect(admin)
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${tool(connection, "find_invoice").id}", [enabled: true])
        def groupId = api.grant(admin, connection.id, user.id, "READ")
        [tenant: tenant, admin: admin, chat: chat, user: user, token: user.token as String, connection: connection,
         groupId: groupId]
    }

    static final String CANCEL_QUESTION = 'Storniere Rechnung 4711. [[call Buchhaltung__cancel_invoice {"number":"4711"}]]'

    /** A tenant whose user may change: its cancel_invoice turned on, the user with WRITE through its group (groupId). */
    private Map writeSetup() {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = chatModel(tenant, admin)
        def user = api.user(tenant.id)
        def connection = connect(admin)
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${tool(connection, "cancel_invoice").id}", [enabled: true])
        def groupId = api.grant(admin, connection.id, user.id, "WRITE")
        [tenant: tenant, admin: admin, chat: chat, user: user, token: user.token as String, connection: connection,
         groupId: groupId]
    }

    private List<Map> messages(String token, Object conversationId) {
        api.call(token, "GET", "/tenant/chat/conversations/${conversationId}/messages") as List<Map>
    }

    /** Moves the tenant's waiting calls back in time. */
    private void ageActions(Object tenantId, Duration age) {
        def dataSource = databases.dataSource(tenants.findById(tenantId as Long).get().databaseName())
        dataSource.connection.withCloseable { connection ->
            connection.prepareStatement("UPDATE pending_action SET created_at = created_at - interval '${age.toHours()} hours'")
                    .withCloseable { it.executeUpdate() }
        }
    }

    private Map connect(String admin) {
        api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Buchhaltung", baseUrl: software.url()]) as Map
    }

    private dev.langchain4j.model.chat.request.json.JsonObjectSchema offeredParameters(String name) {
        (fake.chats.last().toolSpecifications as List<dev.langchain4j.agent.tool.ToolSpecification>)
                .find { it.name() == name }.parameters()
    }

    private static Map tool(Map connection, String name) {
        (connection.tools as List<Map>).find { it.name == name }
    }

    private Map chatModel(Map tenant, String admin) {
        def chat = api.chatModel()
        api.allow(tenant.id, chat)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        chat
    }
}
