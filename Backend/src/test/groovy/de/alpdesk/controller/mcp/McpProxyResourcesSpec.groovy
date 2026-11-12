package de.alpdesk.controller.mcp

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.mcp.FakeMcpSoftware
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * The resources of the connected software passed through to MCP clients (decided 11 November 2026): where a connection
 * passes its resources on ("Ressourcen an MCP-Clients", off by default), a key that passes the software through lists
 * and reads them - their addresses with the connection in front, separated by a dot (`buchhaltung.ticket://4711/log`).
 */
@MicronautTest(transactional = false)
class McpProxyResourcesSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Shared
    AiApi api

    FakeMcpSoftware software

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
        software = new FakeMcpSoftware()
        software.resourcePages[""] = [resources: [
                [uri: "ticket://4711/protokoll.log", name: "protokoll.log", title: "Protokoll", mimeType: "text/plain",
                 _meta: ["com.example/size": 12, "io.modelcontextprotocol/x": 1]],
                [uri: "https://files.example.com/handbuch.pdf", name: "handbuch.pdf"]]]
        software.templatePages[""] = [resourceTemplates: [[uriTemplate: "ticket://{number}/attachment", name: "attachment"]]]
        software.resources["ticket://4711/protokoll.log"] = [uri: "ticket://4711/protokoll.log", mimeType: "text/plain",
                                                             text: "Started"]
    }

    def cleanup() {
        software?.stop()
    }

    def "the switch is off by default - on, the key's client lists the software's resources with the connection in front, a web address as it is"() {
        given:
        def setup = tenantWithSoftware()

        expect: "off: the server has no resources"
        !setup.connection.passesResources
        initialize(setup.key).result.capabilities.resources == null
        rpc(setup.key, "resources/list").error.code == -32601

        when:
        def switched = api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/resources",
                [passesResources: true]) as Map
        def listed = rpc(setup.key, "resources/list").result
        def templates = rpc(setup.key, "resources/templates/list").result

        then:
        switched.passesResources
        initialize(setup.key).result.capabilities.resources != null
        listed.resources == [
                [uri: "buchhaltung.ticket://4711/protokoll.log", name: "[Buchhaltung] protokoll.log",
                 title: "[Buchhaltung] Protokoll", mimeType: "text/plain", _meta: ["com.example/size": 12]],
                [uri: "https://files.example.com/handbuch.pdf", name: "[Buchhaltung] handbuch.pdf"]]
        listed.nextCursor == null
        templates.resourceTemplates == [[uriTemplate: "buchhaltung.ticket://{number}/attachment", name: "[Buchhaltung] attachment"]]
    }

    def "a prefixed address is read at its software with the address as it was - the answer's addresses prefixed again"() {
        given:
        def setup = tenantWithSoftware(true)

        when:
        def read = rpc(setup.key, "resources/read", [uri: "buchhaltung.ticket://4711/protokoll.log"]).result

        then:
        read.contents == [[uri: "buchhaltung.ticket://4711/protokoll.log", mimeType: "text/plain", text: "Started"]]
        software.received.find { it.method == "resources/read" }.params.uri == "ticket://4711/protokoll.log"
    }

    def "#reason is not found - #hint"() {
        given:
        def setup = tenantWithSoftware(true)

        when:
        def error = rpc(setup.key, "resources/read", [uri: uri]).error

        then:
        error.code == -32002
        (error.message as String).startsWith("Resource not found: " + uri)
        (error.message as String).contains(hint)
        !software.received.any { it.method == "resources/read" && it.params.uri == uri }

        where:
        reason                          | uri                                      || hint
        "an address without the prefix" | "ticket://4711/protokoll.log"           || 'start with "<connection>."; list the resources'
        "another connection's prefix"   | "lager.ticket://4711/protokoll.log"     || 'start with "<connection>."; list the resources'
        "a web address"                 | "https://files.example.com/handbuch.pdf" || "fetch it directly"
    }

    def "the software's own not found goes to the client as it answered"() {
        given:
        def setup = tenantWithSoftware(true)

        when:
        def error = rpc(setup.key, "resources/read", [uri: "buchhaltung.ticket://9999/gone"]).error

        then:
        error.code == -32002
        error.message == "Resource not found"
        software.received.find { it.method == "resources/read" }.params.uri == "ticket://9999/gone"
    }

    def "a read reaches the right software among two; the lists come page by page, a page of one software, our cursor carrying its"() {
        given:
        def setup = tenantWithSoftware(true)
        def second = new FakeMcpSoftware()
        second.resourcePages[""] = [resources: [[uri: "build://981/log", name: "log 981"]], nextCursor: "p2"]
        second.resourcePages["p2"] = [resources: [[uri: "build://982/log", name: "log 982"]]]
        second.resources["build://981/log"] = [uri: "build://981/log", text: "Build 981 ok"]
        def build = addSoftware(setup, "Buildserver", second)

        when:
        def first = rpc(setup.key, "resources/list").result
        def next = rpc(setup.key, "resources/list", [cursor: first.nextCursor]).result
        def last = rpc(setup.key, "resources/list", [cursor: next.nextCursor]).result
        def read = rpc(setup.key, "resources/read", [uri: "buildserver.build://981/log"]).result

        then: "in the order of the software's names"
        first.resources*.uri == ["buchhaltung.ticket://4711/protokoll.log", "https://files.example.com/handbuch.pdf"]
        next.resources*.uri == ["buildserver.build://981/log"]
        last.resources*.uri == ["buildserver.build://982/log"]
        last.nextCursor == null
        second.received.find { it.method == "resources/list" && it.params?.cursor == "p2" } != null
        read.contents == [[uri: "buildserver.build://981/log", text: "Build 981 ok"]]
        !software.received.any { it.method == "resources/read" }

        and: "a cursor that is not ours is refused"
        rpc(setup.key, "resources/list", [cursor: "nonsense"]).error.code == -32602

        cleanup:
        second?.stop()
    }

    def "a software out of reach is left out of the list - the other answers"() {
        given:
        def setup = tenantWithSoftware(true)
        def second = new FakeMcpSoftware()
        second.resourcePages[""] = [resources: [[uri: "build://981/log", name: "log 981"]]]
        addSoftware(setup, "Buildserver", second)
        software.stop()

        when:
        def listed = rpc(setup.key, "resources/list").result

        then:
        listed.resources*.uri == ["buildserver.build://981/log"]
        listed.nextCursor == null

        cleanup:
        second?.stop()
    }

    def "no resources for a key without the software or another tenant's key - and a software that stops passing through loses the switch"() {
        given:
        def setup = tenantWithSoftware(true)
        def otherKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Bank"]) as Map).plainKey as String
        def other = api.tenant()
        def otherTenantKey = (api.call(api.tenantAdminToken(other.id), "POST", "/tenant/admin/mcp-keys", [name: "Fremd"]) as Map)
                .plainKey as String
        def address = [uri: "buchhaltung.ticket://4711/protokoll.log"]

        expect:
        rpc(otherKey, "resources/list").error.code == -32601
        rpc(otherKey, "resources/read", address).error.code == -32601
        rpc(otherTenantKey, "resources/read", address).error.code == -32601

        when: "the software no longer passes through - its resources go with it"
        def off = api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/pass-through", [passesThrough: false]) as Map

        then:
        !off.passesResources
        rpc(setup.key, "resources/read", address).error.code == -32601
        refusal(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/resources", [passesResources: true]) ==
                [409, "mcpConnection.resourcesNeedPassThrough"]

        when: "it passes through again - nobody chose its resources"
        def on = api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/pass-through", [passesThrough: true]) as Map

        then:
        !on.passesResources
        rpc(setup.key, "resources/list").error.code == -32601
        !software.received.any { it.method?.startsWith("resources/") }
    }

    def "a key with two software reads only the one whose switch is on"() {
        given:
        def setup = tenantWithSoftware(true)
        def second = new FakeMcpSoftware()
        second.resources["build://981/log"] = [uri: "build://981/log", text: "Build 981 ok"]
        def build = addSoftware(setup, "Buildserver", second, false)

        expect:
        rpc(setup.key, "resources/read", [uri: "buildserver.build://981/log"]).error.code == -32002
        second.received.every { it.method != "resources/read" }

        cleanup:
        second?.stop()
    }

    def "a link to an own resource that could not be embedded gets the prefix where the switch is on - a web link never"() {
        given:
        def setup = tenantWithSoftware(true)
        def web = FakeMcpSoftware.link("https://files.example.com/a.pdf", "a.pdf")
        software.callContent = [FakeMcpSoftware.link("ticket://4711/gone.pdf", "gone.pdf"), web]

        when:
        def result = rpc(setup.key, "tools/call", [name: "Buchhaltung__find_invoice", arguments: [number: "1"]]).result

        then:
        result.content == [[type: "resource_link", uri: "buchhaltung.ticket://4711/gone.pdf", name: "gone.pdf"], web]
    }

    def "the switch: required, an A2A agent has none, another tenant's connection is not found, the admin sets it too"() {
        given:
        def setup = tenantWithSoftware()
        def other = tenantWithSoftware()

        expect:
        refusal(setup.admin, "PUT", "/tenant/admin/mcp-connections/${setup.connection.id}/resources", [:]) ==
                [400, "mcpConnection.passResourcesRequired"]
        refusal(setup.admin, "PUT", "/tenant/admin/mcp-connections/${other.connection.id}/resources",
                [passesResources: true])[0] == 404

        when:
        def set = api.admin("PUT", "/admin/tenants/${setup.tenant.id}/mcp-connections/${setup.connection.id}/resources",
                [passesResources: true]) as Map

        then:
        set.passesResources
        rpc(setup.key, "resources/list").result.resources.size() == 2
    }

    def "an A2A agent passes no resources"() {
        given:
        def agent = new de.alpdesk.services.a2a.FakeA2aAgent()
        def setup = tenantWithSoftware()
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, a2aAllowed: true])
        def connection = api.call(setup.admin, "POST", "/tenant/admin/mcp-connections",
                [name: "Fakeagent", baseUrl: agent.url(), protocol: "A2A"]) as Map

        expect:
        refusal(setup.admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/resources", [passesResources: true]) ==
                [409, "mcpConnection.notForA2a"]

        cleanup:
        agent.stop()
    }

    /** A tenant with a connected software passed through, its reading tool on, and a key given it. */
    private Map tenantWithSoftware(boolean resources = false) {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Buchhaltung", baseUrl: software.url()]) as Map
        enableTools(admin, connection)
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/pass-through", [passesThrough: true])
        if (resources) {
            connection = api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/resources", [passesResources: true]) as Map
        }
        def created = api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude", connectionIds: [connection.id]]) as Map
        [tenant: tenant, admin: admin, connection: connection, key: created.plainKey as String, keyId: created.key.id]
    }

    /** A second software of the key, passed through - its resources too unless said otherwise. */
    private Map addSoftware(Map setup, String name, FakeMcpSoftware other, boolean resources = true) {
        def connection = api.call(setup.admin, "POST", "/tenant/admin/mcp-connections", [name: name, baseUrl: other.url()]) as Map
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/pass-through", [passesThrough: true])
        if (resources) {
            api.call(setup.admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/resources", [passesResources: true])
        }
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-keys/${setup.keyId}", [connectionIds: [setup.connection.id, connection.id]])
        connection
    }

    private void enableTools(String admin, Map connection) {
        (connection.tools as List<Map>).each {
            api.call(admin, "PUT", "/tenant/admin/mcp-connections/${connection.id}/tools/${it.id}", [enabled: true])
        }
    }

    private List refusal(String token, String method, String path, Map body) {
        try {
            api.call(token, method, path, body)
            throw new AssertionError("$method $path was not refused")
        } catch (HttpClientResponseException e) {
            [e.status.code, e.response.getBody(Map).map { it.code }.orElse(null)]
        }
    }

    private Map initialize(String key) {
        rpc(key, "initialize", [protocolVersion: "2025-06-18", capabilities: [:], clientInfo: [name: "claude-code", version: "1"]])
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
}
