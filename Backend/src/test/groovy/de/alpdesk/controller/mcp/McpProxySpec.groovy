package de.alpdesk.controller.mcp

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.mcp.FakeMcpSoftware
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The connected software passed through the tenant's MCP server (roadmap point 41): an MCP key with a software that
 * passes through ("Direkt an MCP-Clients") gets its reading tools in tools/list and calls them itself - no model answers
 * in between. A key without the software, or a software that does not pass through, gets none of them.
 */
@MicronautTest(transactional = false)
class McpProxySpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Shared
    AiApi api

    FakeMcpSoftware software

    /** Through the pool itself: outside a transaction the injected DataSource hands out no connection. */
    DataSource managementDatabase

    @Inject
    void setManagementDatabase(DataSource dataSource) {
        managementDatabase = DelegatingDataSource.unwrapDataSource(dataSource)
    }

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
        software = new FakeMcpSoftware()
    }

    def cleanup() {
        software?.stop()
    }

    def "a key with a software that passes through lists its reading tools - named after it, the changing one never"() {
        given:
        def setup = tenantWithSoftware(true)

        when:
        def tools = toolNames(setup.key)
        def find = (rpc(setup.key, "tools/list").result.tools as List<Map>).find { it.name == "Buchhaltung__find_invoice" }

        then:
        tools.containsAll(["ask_assistant", "search_knowledge", "Buchhaltung__find_invoice"])
        !tools.contains("Buchhaltung__cancel_invoice")
        find.description == "[Buchhaltung] Finds an invoice by its number"
        find.inputSchema.required == ["number"]
        find.annotations.readOnlyHint == true
        software.calls().isEmpty()
    }

    def "a passed-through tool is called at the software with the client's arguments - its answer comes back as it is, no model answers"() {
        given:
        def setup = tenantWithSoftware(true)

        when:
        def result = rpc(setup.key, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "4711"]]).result

        then:
        !result.isError
        result.content[0].text == "Invoice 4711: 412,50 EUR, paid"
        software.calls()*.params*.name == ["find_invoice"]
        software.calls()[0].params.arguments == [number: "4711"]
        fake.chats.isEmpty()
    }

    def "the software's error stays an error the client reads; a software out of reach too"() {
        given:
        def setup = tenantWithSoftware(true)

        when:
        def refused = rpc(setup.key, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "0000"]]).result
        software.stop()
        def unreachable = rpc(setup.key, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "4711"]]).result

        then:
        refused.isError
        refused.content[0].text == "No invoice 0000"
        unreachable.isError
        (unreachable.content[0].text as String).contains("Buchhaltung")
    }

    def "a key of the connection no longer readable is a tool error saying so - the session and our own tools go on"() {
        given: "the connection's key stored so that it cannot be decrypted any more (APP_SECRET_KEY changed)"
        def setup = tenantWithSoftware(true)
        managementDatabase.connection.withCloseable {
            it.prepareStatement("UPDATE mcp_connection SET api_key = 'broken', key_hint = '…oken' WHERE id = ${setup.connection.id}").executeUpdate()
        }

        when:
        def answer = rpc(setup.key, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "4711"]])

        then:
        answer.error == null
        answer.result.isError
        answer.result.content[0].text == "Buchhaltung: our stored key can no longer be read - enter it again in the connection"
        toolNames(setup.key).containsAll(["ask_assistant", "Buchhaltung__find_invoice"])
    }

    def "a passed-through software signed in with OAuth: its tool is listed and called with the token - which never goes to the client"() {
        given:
        def authServer = new de.alpdesk.services.oauth.FakeAuthorizationServer()
        software.oauth = authServer
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def created = api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Buchhaltung", baseUrl: software.url()]) as Map
        def started = api.call(admin, "POST", "/tenant/admin/mcp-connections/${created.id}/sign-in",
                [returnUrl: "http://127.0.0.1:4300/app/software"]) as Map
        def browser = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
        def atServer = browser.send(java.net.http.HttpRequest.newBuilder(URI.create(started.authorizationUrl as String)).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding())
        def back = browser.send(java.net.http.HttpRequest.newBuilder(URI.create(atServer.headers().firstValue("Location").get())).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding())
        assert api.completeSignIn(admin, "/tenant/admin/mcp-connections", back.headers().firstValue("Location").get())
                .endsWith("signIn=ok")
        def connection = (api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>).find { it.id == created.id }
        enableReading(admin, connection)
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/pass-through", [passesThrough: true])
        String key = (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude", connectionIds: [connection.id]]) as Map).plainKey

        when:
        def tools = toolNames(key)
        def result = rpc(key, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "4711"]]).result

        then:
        tools.contains("Buchhaltung__find_invoice")
        !result.isError
        result.content[0].text == "Invoice 4711: 412,50 EUR, paid"
        authServer.valid(software.calls().last().authorization)
        !authServer.issuedTokens.any { result.toString().contains(it) }

        cleanup:
        authServer.stop()
    }

    def "a software that does not pass through is answer-only: none of its tools is listed, a call by name is an unknown tool"() {
        given:
        def setup = tenantWithSoftware(false)

        expect:
        !toolNames(setup.key).any { it.contains("__") }
        unknownTool(rpc(setup.key, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "4711"]]))
        software.calls().isEmpty()

        when: "switched on"
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/pass-through", [passesThrough: true])

        then:
        toolNames(setup.key).contains("Buchhaltung__find_invoice")
    }

    def "only the keys given the software get it - another key of the tenant and another tenant's key do not"() {
        given:
        def setup = tenantWithSoftware(true)
        def otherKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Bank"]) as Map).plainKey as String
        def other = api.tenant()
        def otherTenantKey = (api.call(api.tenantAdminToken(other.id), "POST", "/tenant/admin/mcp-keys", [name: "Fremd"]) as Map)
                .plainKey as String

        expect:
        !toolNames(otherKey).any { it.contains("__") }
        unknownTool(rpc(otherKey, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "4711"]]))
        unknownTool(rpc(otherTenantKey, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "4711"]]))
        software.calls().isEmpty()

        when: "the key's software is taken away"
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-keys/${setup.keyId}", [connectionIds: []])

        then:
        !toolNames(setup.key).any { it.contains("__") }
    }

    def "a tool turned off or no longer offered is not passed through"() {
        given:
        def setup = tenantWithSoftware(true)
        def find = (setup.connection.tools as List<Map>).find { it.name == "find_invoice" }

        when:
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/tools/${find.id}", [enabled: false])

        then:
        !toolNames(setup.key).contains("Buchhaltung__find_invoice")
        unknownTool(rpc(setup.key, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "4711"]]))
    }

    def "files the software returns go to the client as resources - text as text, the rest as base64, types kept to the list"() {
        given:
        def setup = tenantWithSoftware(true)
        def zip = zipOf("Main.java": "class Main {}")
        software.callContent = [[type: "text", text: "Generated"],
                                FakeMcpSoftware.blob("file:///demo-project.zip", "application/zip", zip),
                                [type: "resource", resource: [uri: "file:///Main.java", mimeType: "text/x-java", text: "class Main {}"]],
                                FakeMcpSoftware.blob("file:///page.html", "text/html", "<script>x</script>".getBytes("UTF-8"))]

        when:
        def result = rpc(setup.key, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "1"]]).result
        def resources = result.content.findAll { it.type == "resource" }*.resource as List<Map>

        then:
        !result.isError
        result.content[0].text == "Generated"
        resources*.uri == ["file:///demo-project.zip", "file:///Main.java", "file:///page.html"]
        Base64.decoder.decode(resources[0].blob as String) == zip
        resources[1].text == "class Main {}"
        resources[2].mimeType == "text/plain"
        resources[2].text == "<script>x</script>"
    }

    def "a file the software links to goes to the client as a resource with its content - the link itself never"() {
        given:
        def setup = tenantWithSoftware(true)
        software.downloads["signed-4711"] = [body: "%PDF-1.4".getBytes("UTF-8"), type: "application/pdf"]
        software.callContent = [[type: "text", text: "Rechnung"],
                                FakeMcpSoftware.link(software.fileUrl("signed-4711"), "rechnung-4711.pdf")]

        when:
        def result = rpc(setup.key, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "1"]]).result
        def resources = result.content.findAll { it.type == "resource" }*.resource as List<Map>

        then:
        !result.isError
        resources*.uri == ["file:///rechnung-4711.pdf"]
        resources[0].mimeType == "application/pdf"
        Base64.decoder.decode(resources[0].blob as String) == "%PDF-1.4".getBytes("UTF-8")
        !result.content.any { it.type == "resource_link" }
        !(result.toString().contains(software.fileUrl("signed-4711")))
    }

    def "a name that does not fit ends in its tool's id - each calls its own software, and none moves when another tool is turned off"() {
        given: "two software of the same name part, and two tools that cut to the same name"
        def setup = tenantWithSoftware(true, "Sehr lange Buchhaltungssoftware der Musterfirma")
        def warehouse = new FakeMcpSoftware()
        warehouse.tools = [readingTool("get_customer_by_id"), readingTool("get_customer_by_name")]
        def second = api.call(setup.admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Sehr lange Buchhaltungssoftware der Musterfirma 2", baseUrl: warehouse.url()]) as Map
        enableReading(setup.admin, second)
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${second.id}/pass-through", [passesThrough: true])
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-keys/${setup.keyId}", [connectionIds: [setup.connection.id, second.id]])
        def toolIds = ((setup.connection.tools + second.tools) as List<Map>).collectEntries { [(it.name): it.id] }

        when:
        def names = passedNames(setup.key, toolIds)

        then: "every name ends in its tool's id - the changing tool is not among them"
        names.keySet() == ["find_invoice", "get_customer_by_id", "get_customer_by_name"] as Set
        names.values().every { it.length() <= 32 }

        and: "each name calls its own tool at its own software - as listed"
        names.values().every { !rpc(setup.key, "tools/call", [name: it, arguments: [number: "4711"]]).result.isError }
        software.calls()*.params*.name == ["find_invoice"]
        warehouse.calls()*.params*.name == ["get_customer_by_id", "get_customer_by_name"]

        when: "one of the two is turned off - a client holding the old list calls both names again"
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${second.id}/tools/${toolIds.get_customer_by_id}", [enabled: false])

        then: "the other keeps its name, the turned-off one's name reaches nothing"
        passedNames(setup.key, toolIds) == names.findAll { it.key != "get_customer_by_id" }
        unknownTool(rpc(setup.key, "tools/call", [name: names.get_customer_by_id, arguments: [number: "4711"]]))
        warehouse.calls().size() == 2

        cleanup:
        warehouse?.stop()
    }

    def "a changing tool called by its name is an unknown tool - the software is never asked"() {
        given:
        def setup = tenantWithSoftware(true)

        expect:
        unknownTool(rpc(setup.key, "tools/call", [name: "Buchhaltung__cancel_invoice", arguments: [number: "4711"]]))
        software.calls().isEmpty()
    }

    def "ask_assistant of a key with the software still answers through the assistant - the same tools, offered to its model"() {
        given:
        def setup = tenantWithSoftware(true)

        when:
        def text = rpc(setup.key, "tools/call", [name: "ask_assistant", arguments: [
                question: 'Rechnung? [[call Buchhaltung__find_invoice {"number":"4711"}]]']]).result.content[0].text as String

        then:
        fake.chats.last().tools.contains("Buchhaltung__find_invoice")
        text.contains("Invoice 4711: 412,50 EUR, paid")
    }

    def "the software's instructions are kept when its tools are read - cut to 2,000 characters - and follow ours for a key that passes it through"() {
        given:
        software.instructions = "Call find_invoice with the invoice's number. " + ("x" * 2_500)
        def setup = tenantWithSoftware(true)
        def plainKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Bank"]) as Map).plainKey as String

        when:
        def instructions = initialize(setup.key).result.instructions as String

        then:
        (setup.connection.instructions as String).length() == 2_000
        (setup.connection.instructions as String).startsWith("Call find_invoice with the invoice's number.")
        instructions.startsWith("It can also read the connected software Buchhaltung - hand it those parts of a "
                + "question that concern the company, with their full context.\n\nZirmo is the AI assistant of the company")
        instructions.contains("\n\nSoftware Buchhaltung (its tools' descriptions start with [Buchhaltung]): Call find_invoice")

        and: "a key without the software, or the software not passed through, gets ours only"
        !(initialize(plainKey).result.instructions as String).contains("Software Buchhaltung")

        when:
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/pass-through", [passesThrough: false])

        then:
        !(initialize(setup.key).result.instructions as String).contains("Software Buchhaltung")
    }

    def "our own assistant reads the software's instructions where its tools are offered - also when it is not passed through"() {
        given:
        software.instructions = "Call find_invoice with the invoice's number."
        def setup = tenantWithSoftware(false)

        when:
        rpc(setup.key, "tools/call", [name: "ask_assistant", arguments: [question: "Rechnung 4711?"]])
        String system = fake.chats.last().messages[0].text()

        then:
        system.contains("What the software itself says about its tools")
        system.contains("- Buchhaltung: Call find_invoice with the invoice's number.")

        when: "a key without the software"
        def plainKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Bank"]) as Map).plainKey as String
        rpc(plainKey, "tools/call", [name: "ask_assistant", arguments: [question: "Rechnung 4711?"]])

        then:
        !fake.chats.last().messages[0].text().contains("Call find_invoice")
    }

    def "a software that gives no instructions adds none; a new address drops the old ones"() {
        given:
        software.instructions = "Old words."
        def setup = tenantWithSoftware(true)
        def other = new FakeMcpSoftware()

        when:
        def moved = api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}",
                [name: "Buchhaltung", baseUrl: other.url()]) as Map

        then:
        setup.connection.instructions == "Old words."
        moved.instructions == null
        !(initialize(setup.key).result.instructions as String).contains("Software Buchhaltung")

        cleanup:
        other?.stop()
    }

    def "a key that passes the software through gets its turned-on prompts - their text from the software with the client's values"() {
        given:
        def setup = tenantWithSoftware(true)
        def prompt = (setup.connection.prompts as List<Map>)[0]
        def plainKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Bank"]) as Map).plainKey as String

        expect: "off until turned on"
        !promptNames(setup.key).contains("Buchhaltung__create_test_program")

        when:
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/prompts/${prompt.id}", [enabled: true])
        def listed = (rpc(setup.key, "prompts/list").result.prompts as List<Map>).find { it.name == "Buchhaltung__create_test_program" }
        def got = rpc(setup.key, "prompts/get", [name: "Buchhaltung__create_test_program",
                                                 arguments: [package_name: "de.muster", project_name: "Demo"]]).result

        then:
        listed.description == "[Buchhaltung] Creates a test program"
        listed.title == "Prüfprogramm erstellen"
        (listed.arguments as List<Map>).collect { [it.name, it.required] } ==
                [["package_name", true], ["project_name", true], ["comment", false]]
        got.messages.size() == 1
        got.messages[0].role == "user"
        got.messages[0].content.text == "Erstelle ein Prüfprogramm für de.muster im Projekt Demo.\n\nAnmerkung: keine"

        and: "a required value missing is refused by us, before the software is asked - as the prompt's text"
        promptText(rpc(setup.key, "prompts/get", [name: "Buchhaltung__create_test_program", arguments: [package_name: "de.muster"]])) ==
                'The prompt Buchhaltung__create_test_program could not be fetched: The value "project_name" is required'

        and: "our own templates stay, another key gets none of the software's"
        !promptNames(plainKey).contains("Buchhaltung__create_test_program")
        promptText(rpc(plainKey, "prompts/get", [name: "Buchhaltung__create_test_program", arguments: [:]]))
                .startsWith("Unknown prompt: Buchhaltung__create_test_program")
    }

    def "a prompt the software refuses or cannot give is the prompt's text saying so - never a JSON-RPC error"() {
        given:
        def setup = tenantWithSoftware(true)
        def prompt = (setup.connection.prompts as List<Map>)[0]
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/prompts/${prompt.id}", [enabled: true])
        def values = [name: "Buchhaltung__create_test_program", arguments: [package_name: "de.muster", project_name: "Demo"]]

        when:
        software.promptError = "Invalid package name"
        def refused = rpc(setup.key, "prompts/get", values)
        software.stop()
        def unreachable = rpc(setup.key, "prompts/get", values)

        then:
        promptText(refused).startsWith("The prompt Buchhaltung__create_test_program could not be fetched: ")
        promptText(refused).contains("Invalid package name")
        promptText(unreachable).startsWith("The prompt Buchhaltung__create_test_program could not be fetched: ")
        promptText(unreachable).contains("Buchhaltung")

        and: "the session goes on"
        promptNames(setup.key).contains("Buchhaltung__create_test_program")
    }

    def "a name listed before the connection was #change is an unknown tool - the software is never asked"() {
        given:
        def setup = tenantWithSoftware(true)
        def listed = toolNames(setup.key)

        when:
        if (change == "renamed") {
            api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}",
                    [name: "Faktura", baseUrl: software.url()])
        } else {
            api.status(setup.admin, "DELETE", "/tenant/admin/mcp-connections/${setup.connection.id}")
        }

        then:
        listed.contains("Buchhaltung__find_invoice")
        unknownTool(rpc(setup.key, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "4711"]]))
        software.calls().isEmpty()
        toolNames(setup.key).contains(change == "renamed" ? "Faktura__find_invoice" : "ask_assistant")

        where:
        change << ["renamed", "deleted"]
    }

    /** A tenant with a chat model, a connected software with its reading tool on and a key given it. */
    private Map tenantWithSoftware(boolean passesThrough, String name = "Buchhaltung") {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = api.chatModel()
        def embedding = api.embeddingModel()
        api.allow(tenant.id, chat, embedding)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        api.call(admin, "PUT", "/tenant/admin/assistant", [instructions: "", answerSource: "DOCUMENTS_ONLY",
                creativity: "MEDIUM", reasoning: "LOW", searchResults: 5, defaultModelId: chat.id])
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: name, baseUrl: software.url()]) as Map
        enableReading(admin, connection)
        if (passesThrough) {
            api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/pass-through", [passesThrough: true])
        }
        def created = api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude", connectionIds: [connection.id]]) as Map
        [tenant: tenant, admin: admin, connection: connection, key: created.plainKey as String, keyId: created.key.id]
    }

    /** Both tools on - the changing one must stay back all the same. */
    private void enableReading(String admin, Map connection) {
        (connection.tools as List<Map>).each {
            api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${it.id}", [enabled: true])
        }
    }

    /** A passed-through name the key does not have: a tool error, so the client lists again - not a JSON-RPC error. */
    private static boolean unknownTool(Map answer) {
        answer.result?.isError && (answer.result.content[0].text as String).startsWith("Unknown tool: ")
    }

    /** The one user message of a prompts/get - fails on a JSON-RPC error. */
    private static String promptText(Map answer) {
        assert answer.error == null
        def messages = answer.result.messages as List<Map>
        assert messages.size() == 1 && messages[0].role == "user"
        messages[0].content.text as String
    }

    private List<String> promptNames(String key) {
        (rpc(key, "prompts/list").result.prompts as List<Map>)*.name as List<String>
    }

    private Map initialize(String key) {
        rpc(key, "initialize", [protocolVersion: "2025-06-18", capabilities: [:], clientInfo: [name: "claude-code", version: "1"]])
    }

    private List<String> toolNames(String key) {
        (rpc(key, "tools/list").result.tools as List<Map>)*.name as List<String>
    }

    /** The key's passed-through tools by the software's tool name, for names that end in their tool's id. */
    private Map<String, String> passedNames(String key, Map<String, Object> toolIds) {
        def passed = toolNames(key).findAll { it.contains("__") }
        assert passed.every { name -> toolIds.values().any { name.endsWith("__" + it) } }
        toolIds.findAll { tool, id -> passed.any { it.endsWith("__" + id) } }
                .collectEntries { tool, id -> [(tool): passed.find { it.endsWith("__" + id) }] }
    }

    private static Map readingTool(String name) {
        [name: name, description: name, inputSchema: [type: "object", properties: [number: [type: "string"]]],
         annotations: [readOnlyHint: true]]
    }

    private Map rpc(String key, String method, Map params = null) {
        Map message = [jsonrpc: "2.0", id: UUID.randomUUID().toString(), method: method]
        if (params != null) {
            message.params = params
        }
        try {
            client.toBlocking().retrieve(HttpRequest.POST("/mcp", message).bearerAuth(key), Map) as Map
        } catch (HttpClientResponseException e) {
            e.response.getBody(Map).orElse([:]) as Map
        }
    }

    private static byte[] zipOf(Map<String, String> entries) {
        def out = new ByteArrayOutputStream()
        new ZipOutputStream(out).withCloseable { zip ->
            entries.each { name, text ->
                zip.putNextEntry(new ZipEntry(name))
                zip.write(text.getBytes("UTF-8"))
                zip.closeEntry()
            }
        }
        out.toByteArray()
    }
}
