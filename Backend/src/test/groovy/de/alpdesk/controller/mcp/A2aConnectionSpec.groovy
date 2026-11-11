package de.alpdesk.controller.mcp

import de.alpdesk.services.a2a.A2aClient
import de.alpdesk.services.a2a.FakeA2aAgent
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.mcp.McpAddresses
import de.alpdesk.services.oauth.FakeAuthorizationServer
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.a2aproject.sdk.spec.AgentInterface
import spock.lang.Shared
import spock.lang.Specification

import java.time.Duration

/**
 * A connected software speaking A2A (docs/architecture/a2a.md, roadmap point 54 package A): the agent's card read into
 * the connection's instructions and skills, the agent offered to the chat's model as one tool <connection>__ask, its
 * answer, files, question back and task - only for a tenant the admin allowed A2A.
 */
@MicronautTest(transactional = false)
class A2aConnectionSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    McpAddresses addresses

    @Value('${app.ai.timeout}')
    Duration timeout

    @Shared
    AiApi api

    FakeA2aAgent agent = new FakeA2aAgent()

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def cleanup() {
        agent.stop()
    }

    def "an A2A agent needs the admin's switch; its card gives the instructions and the skills - all on, no prompts"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        agent.key = "agent-key-123456"

        expect: "without the switch"
        api.status(admin, "POST", "/tenant/admin/mcp-connections", request()) == HttpStatus.CONFLICT
        api.error(admin, "POST", "/tenant/admin/mcp-connections", request()) ==
                "The tenant may not connect A2A agents - the admin allows it"

        when:
        allowA2a(tenant)
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections", request()) as Map

        then:
        connection.protocol == "A2A"
        connection.toolsError == null
        connection.instructions == "Fakeagent\nEin Agent der Specs, der Fragen beantwortet"
        (connection.tools as List<Map>).collect { [it.name, it.description, it.enabled, it.changes] } == [
                ["rechnungen", "Rechnungen: Prüft Rechnungen", true, false],
                ["urlaub", "Urlaub: Plant Urlaube", true, false]]
        connection.prompts == []
        connection.keyHint == "…3456"
        agent.received.every { it.authorization == null || it.authorization == "Bearer agent-key-123456" }

        and: "an MCP connection stays MCP when the protocol is left out"
        (api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>)*.protocol == ["A2A"]
    }

    def "the protocol cannot change, an agent passes nothing to MCP clients, and a reload replaces the skills"() {
        given:
        def setup = connected()
        def path = "/tenant/admin/mcp-connections/${setup.connection.id}"

        expect:
        api.status(setup.admin, "PUT", path, [name: "Fakeagent", baseUrl: agent.url(), protocol: "MCP"]) == HttpStatus.BAD_REQUEST
        api.status(setup.admin, "PUT", "$path/pass-through", [passesThrough: true]) == HttpStatus.CONFLICT
        (api.call(setup.admin, "PUT", path, [name: "Fakeagent", baseUrl: agent.url(), protocol: "A2A"]) as Map).protocol == "A2A"

        when:
        agent.skills = [org.a2aproject.sdk.spec.AgentSkill.builder().id("lager").name("Lager").description("Kennt den Bestand")
                                .tags(["lager"]).build()]
        agent.cardDescription = "Neue Beschreibung"
        def reloaded = api.call(setup.admin, "POST", "$path/reload") as Map

        then:
        (reloaded.tools as List<Map>)*.name == ["lager"]
        reloaded.instructions == "Fakeagent\nNeue Beschreibung"
    }

    def "a skill named twice by the card is kept once"() {
        given:
        def skill = org.a2aproject.sdk.spec.AgentSkill.builder().id("lager").name("Lager").description("Bestand")
                .tags(["lager"]).build()
        agent.skills = [skill, skill]

        when:
        def setup = connected()

        then:
        (setup.connection.tools as List<Map>)*.name == ["lager"]
    }

    def "a card the client cannot use keeps the connection and says why"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        allowA2a(tenant)

        when: "no JSON-RPC interface"
        agent.interfaces = [new AgentInterface("GRPC", "http://127.0.0.1:1/grpc", null, "1.0")]
        def grpc = api.call(admin, "POST", "/tenant/admin/mcp-connections", request("Nur gRPC")) as Map

        then:
        grpc.toolsError == "Nur gRPC offers no JSON-RPC interface - the only binding we speak"
        grpc.tools == []

        when: "no card at all"
        agent.rawCard = '{"name": 42'
        def broken = api.call(admin, "POST", "/tenant/admin/mcp-connections", request("Kaputt")) as Map

        then:
        (broken.toolsError as String).startsWith("Kaputt offers no valid agent card")
    }

    def "the chat's model asks the agent through one tool, with a skill and who asks - answered with the connection's name in front"() {
        given:
        def setup = connected()
        def user = api.user(setup.tenant.id)
        def chat = chatModel(setup.tenant, setup.admin)
        api.grant(setup.admin, setup.connection.id, user.id, "READ")

        when:
        def answer = api.ask(user.token as String, [modelId: chat.id, message:
                'Prüfe R-1 [[call Fakeagent__ask {"question":"Ist R-1 bezahlt?","skill":"rechnungen"}]]']).answer as Map

        then:
        answer.content.contains("(Software: [Fakeagent] Antwort von Fakeagent: Ist R-1 bezahlt?)")
        fake.chats.last().tools.contains("Fakeagent__ask")
        def sent = agent.messages().last()
        sent.authorization == "Bearer agent-key-123456"
        sent.version == "1.0"
        sent.extensions == A2aClient.ASKER_EXTENSION
        sent.messageExtensions == [A2aClient.ASKER_EXTENSION]
        def asker = (sent.metadata as Map)[A2aClient.ASKER_EXTENSION] as Map
        asker.tenant == setup.tenant.name
        asker.user == user.username
        (asker.groups as List).size() == 2 && asker.groups.contains("Standard")
                && (asker.groups as List).any { (it as String).startsWith("Gruppe ") }
        asker.idmGroups == []
        asker.language != null
        asker.skills == ["rechnungen"]
        offeredDescription("Fakeagent__ask").contains("Ein Agent der Specs")
        offeredDescription("Fakeagent__ask").contains("- rechnungen (Rechnungen: Prüft Rechnungen)")
    }

    def "without a level, or with the admin's switch off again, the agent is not offered"() {
        given:
        def setup = connected()
        def user = api.user(setup.tenant.id)
        def chat = chatModel(setup.tenant, setup.admin)

        when:
        api.ask(user.token as String, [modelId: chat.id, message: "Hallo"])

        then:
        !fake.chats.last().tools.contains("Fakeagent__ask")

        when:
        api.grant(setup.admin, setup.connection.id, user.id, "READ")
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, a2aAllowed: false])
        api.ask(user.token as String, [modelId: chat.id, message: "Hallo"])

        then:
        !fake.chats.last().tools.contains("Fakeagent__ask")
        api.status(setup.admin, "POST", "/tenant/admin/mcp-connections/${setup.connection.id}/reload") == HttpStatus.CONFLICT
    }

    def "the agent's answers: a file returned, a data part, a question back, a task waited for, a failure"() {
        given:
        def setup = connected()
        def user = api.user(setup.tenant.id)
        def chat = chatModel(setup.tenant, setup.admin)
        api.grant(setup.admin, setup.connection.id, user.id, "READ")
        def ask = { String question ->
            api.ask(user.token as String, [modelId: chat.id, message:
                    "Frage [[call Fakeagent__ask {\"question\":\"$question\"}]]".toString()]).answer as Map
        }

        when:
        def withFile = ask("Bericht [[file bericht.txt]] [[data]]")

        then:
        (withFile.files as List<Map>)*.fileName == ["bericht.txt"]
        withFile.content.contains('"betrag":42') && withFile.content.contains('"waehrung":"EUR"')
        withFile.content.contains("Inhalt von bericht.txt")

        expect:
        ask("Buchen [[ask-back Welches Konto?]]").content.contains("(Software: [Fakeagent] The agent asks back: Welches Konto?)")
        ask("Suche [[task]]").content.contains("(Software: [Fakeagent] Antwort von Fakeagent: Suche)")
        ask("Buchen [[failed-task]]").content.contains("The agent's task ended failed: Die Buchung schlug fehl")
        ask("Kaputt [[fail]]").content.contains("Fakeagent answered with an error: Der Agent ist gestört")
    }

    def "another tenant's A2A connection is unknown"() {
        given:
        def setup = connected()
        def other = api.tenantAdminToken(api.tenant().id)

        expect:
        api.status(other, "POST", "/tenant/admin/mcp-connections/${setup.connection.id}/reload") == HttpStatus.NOT_FOUND
        api.status(other, "GET", "/tenant/admin/mcp-connections/${setup.connection.id}/access") == HttpStatus.NOT_FOUND
    }

    def "the key goes to the connection's address only: a card naming another host is called at the address entered"() {
        given:
        agent.interfaces = [new AgentInterface("JSONRPC", "http://localhost:${agent.http.address.port}/elsewhere".toString(), null, "1.0")]
        def setup = connected()
        def user = api.user(setup.tenant.id)
        def chat = chatModel(setup.tenant, setup.admin)
        api.grant(setup.admin, setup.connection.id, user.id, "READ")

        when:
        def answer = api.ask(user.token as String, [modelId: chat.id, message:
                'Frage [[call Fakeagent__ask {"question":"Hallo"}]]']).answer as Map

        then: "answered through /a2a of the address entered, not /elsewhere"
        answer.content.contains("Antwort von Fakeagent: Hallo")
    }

    def "the adapter refuses to send the key to another host"() {
        given:
        def adapter = new de.alpdesk.services.a2a.A2aHttpClient(java.net.http.HttpClient.newHttpClient(), addresses,
                "Fakeagent", agent.url(), "agent-key-123456", timeout)

        when:
        adapter.createPost().url("http://localhost:${agent.http.address.port}/a2a").body("{}").post()

        then:
        thrown(de.alpdesk.services.a2a.A2aHttpClient.KeyLeak)
    }

    def "an agent's redirect is never followed - the key cannot be sent on to another host"() {
        given: "an agent answering every request with a redirect to another server"
        def elsewhere = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        def reached = new java.util.concurrent.atomic.AtomicInteger()
        elsewhere.createContext("/") { exchange ->
            reached.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        elsewhere.start()
        def redirecting = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        redirecting.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${elsewhere.address.port}${exchange.requestURI.path}")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        redirecting.start()
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        allowA2a(tenant)

        when:
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Umleiter",
                baseUrl: "http://127.0.0.1:${redirecting.address.port}/a2a".toString(), protocol: "A2A",
                apiKey: "agent-key-123456"]) as Map

        then:
        connection.toolsError != null
        reached.get() == 0

        cleanup:
        redirecting.stop(0)
        elsewhere.stop(0)
    }

    def "an answer larger than 12 MB is not taken"() {
        given:
        def large = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        large.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            byte[] megabyte = new byte[1024 * 1024]
            try {
                13.times { exchange.responseBody.write(megabyte) }
            } catch (IOException ignored) {
                // The client went away at its limit.
            }
            exchange.close()
        }
        large.start()
        String url = "http://127.0.0.1:${large.address.port}/a2a"
        def adapter = new de.alpdesk.services.a2a.A2aHttpClient(java.net.http.HttpClient.newHttpClient(), addresses,
                "Fakeagent", url, null, timeout)

        when:
        adapter.createPost().url(url).body("{}").post()

        then:
        def e = thrown(IOException)
        e.message == "Fakeagent sent an answer larger than 12 MB"

        cleanup:
        large.stop(0)
    }

    def "an answer that stalls after its headers ends at the deadline - no thread waits on its body for good"() {
        given: "a server sending its headers and then nothing"
        def stalling = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        stalling.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.flush()
            Thread.sleep(10_000)
            exchange.close()
        }
        stalling.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        stalling.start()
        String url = "http://127.0.0.1:${stalling.address.port}/a2a"
        def adapter = new de.alpdesk.services.a2a.A2aHttpClient(java.net.http.HttpClient.newHttpClient(), addresses,
                "Fakeagent", url, null, Duration.ofSeconds(1))

        when:
        long started = System.nanoTime()
        adapter.createPost().url(url).body("{}").post()

        then:
        thrown(java.net.http.HttpTimeoutException)
        Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5)

        cleanup:
        stalling.stop(0)
    }

    def "an agent whose card names an OAuth 2.0 scheme: skills from the public card, a sign-in, the token on every question - renewed once after a 401"() {
        given:
        def authServer = new FakeAuthorizationServer()
        agent.oauth = authServer
        def tenant = api.tenant()
        allowA2a(tenant)
        def admin = api.tenantAdminToken(tenant.id)

        when:
        def created = api.call(admin, "POST", "/tenant/admin/mcp-connections", request()) as Map

        then:
        created.toolsError == null
        created.signIn.status == "AVAILABLE"
        created.signIn.scopesOffered == ["read"]
        (created.tools as List<Map>)*.name == ["rechnungen", "urlaub"]
        agent.received.isEmpty()

        when:
        String back = signIn(admin, created.id)
        def user = api.user(tenant.id)
        def chat = chatModel(tenant, admin)
        api.grant(admin, created.id, user.id, "READ")
        def answer = api.ask(user.token as String, [modelId: chat.id, message: QUESTION]).answer as Map

        then:
        back.endsWith("signIn=ok")
        answer.content.contains("Antwort von Fakeagent: Ist R-1 bezahlt?")
        authServer.valid(agent.messages().last().authorization)

        when: "the token is refused: renewed once, the question repeated once"
        authServer.revoke(agent.messages().last().authorization.substring(7))
        def again = api.ask(user.token as String, [modelId: chat.id, message: QUESTION]).answer as Map

        then:
        again.content.contains("Antwort von Fakeagent: Ist R-1 bezahlt?")
        authServer.refreshes.get() == 1

        when: "refused for good"
        authServer.refuseRefresh = true
        authServer.revoke(agent.messages().last().authorization.substring(7))
        def refused = api.ask(user.token as String, [modelId: chat.id, message: QUESTION]).answer as Map

        then:
        refused.content.contains("Fakeagent: needs a sign-in")
        ((api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>)[0].signIn as Map).status == "SIGN_IN_NEEDED"

        cleanup:
        authServer.stop()
    }

    def "a token refused while the agent's task is asked for is renewed - the question is never sent twice"() {
        given:
        def setup = signedInAgent()
        agent.slowMillis = 1500
        agent.revokeOnNextPoll = true

        when:
        def answer = api.ask(setup.token, [modelId: setup.chat.id, message:
                'Prüfe R-2 [[call Fakeagent__ask {"question":"Ist R-2 bezahlt? [[running]]","skill":"rechnungen"}]]']).answer as Map

        then:
        answer.content.contains("Fertig: Ist R-2 bezahlt?")
        agent.messages().size() == 1
        agent.received.any { it.method == "GetTask" }
        setup.authServer.refreshes.get() == 1

        cleanup:
        setup.authServer.stop()
    }

    def "an agent answering 403 (more rights wanted) is not renewed - the question fails readably, a renewed token refused again loses the sign-in"() {
        given:
        def setup = signedInAgent()

        when:
        agent.forbidden = true
        def forbidden = api.ask(setup.token, [modelId: setup.chat.id, message: QUESTION]).answer as Map

        then:
        forbidden.content.contains("Fakeagent: needs a sign-in")
        setup.authServer.refreshes.get() == 0
        signInOf(setup.admin).status == "SIGNED_IN"

        when: "the agent takes no token of this sign-in any more - renewed once, refused again"
        agent.forbidden = false
        agent.key = "a-key-nobody-has"
        def lost = api.ask(setup.token, [modelId: setup.chat.id, message: QUESTION]).answer as Map

        then:
        lost.content.contains("Fakeagent: needs a sign-in")
        setup.authServer.refreshes.get() == 1
        signInOf(setup.admin).status == "SIGN_IN_NEEDED"

        cleanup:
        setup.authServer.stop()
    }

    private Map signedInAgent() {
        def authServer = new FakeAuthorizationServer()
        agent.oauth = authServer
        def tenant = api.tenant()
        allowA2a(tenant)
        def admin = api.tenantAdminToken(tenant.id)
        def created = api.call(admin, "POST", "/tenant/admin/mcp-connections", request()) as Map
        assert signIn(admin, created.id).endsWith("signIn=ok")
        def user = api.user(tenant.id)
        def chat = chatModel(tenant, admin)
        api.grant(admin, created.id, user.id, "READ")
        [authServer: authServer, admin: admin, token: user.token as String, chat: chat]
    }

    private Map signInOf(String admin) {
        (api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>)[0].signIn as Map
    }

    def "an agent taking a key or a sign-in: a connection with the key is answered with it - no sign-in asked for, also after a reload"() {
        given:
        def authServer = new FakeAuthorizationServer()
        agent.oauth = authServer
        agent.keyOrSignIn = true
        agent.key = "agent-key-123456"
        def tenant = api.tenant()
        allowA2a(tenant)
        def admin = api.tenantAdminToken(tenant.id)

        when:
        def created = api.call(admin, "POST", "/tenant/admin/mcp-connections", request()) as Map
        def reloaded = api.call(admin, "POST", "/tenant/admin/mcp-connections/${created.id}/reload") as Map
        def user = api.user(tenant.id)
        def chat = chatModel(tenant, admin)
        api.grant(admin, created.id, user.id, "READ")
        def answer = api.ask(user.token as String, [modelId: chat.id, message: QUESTION]).answer as Map

        then:
        created.signIn == null
        reloaded.signIn == null
        reloaded.toolsError == null
        answer.content.contains("Antwort von Fakeagent: Ist R-1 bezahlt?")
        agent.messages().last().authorization == "Bearer agent-key-123456"

        when: "the key removed - now the sign-in is offered"
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${created.id}",
                [name: "Fakeagent", baseUrl: agent.url(), removeApiKey: true])
        def keyless = api.call(admin, "POST", "/tenant/admin/mcp-connections/${created.id}/reload") as Map

        then:
        keyless.signIn.status == "AVAILABLE"

        cleanup:
        authServer.stop()
    }

    def "a tenant the admin took A2A away from starts no sign-in of its agent"() {
        given:
        def authServer = new FakeAuthorizationServer()
        agent.oauth = authServer
        def tenant = api.tenant()
        allowA2a(tenant)
        def admin = api.tenantAdminToken(tenant.id)
        def created = api.call(admin, "POST", "/tenant/admin/mcp-connections", request()) as Map
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, a2aAllowed: false])
        int before = authServer.received.size()

        expect: "nothing sent to the server, no registration"
        api.status(admin, "POST", "/tenant/admin/mcp-connections/${created.id}/sign-in", [returnUrl: RETURN]) ==
                HttpStatus.CONFLICT
        authServer.received.size() == before

        cleanup:
        authServer.stop()
    }

    def "a card with the flow's URLs only: the client id comes by hand, then the sign-in works"() {
        given:
        def authServer = new FakeAuthorizationServer()
        agent.oauth = authServer
        agent.cardWithFlowUrls = true
        def tenant = api.tenant()
        allowA2a(tenant)
        def admin = api.tenantAdminToken(tenant.id)
        def created = api.call(admin, "POST", "/tenant/admin/mcp-connections", request()) as Map

        expect:
        created.signIn.status == "AVAILABLE"
        api.status(admin, "POST", "/tenant/admin/mcp-connections/${created.id}/sign-in",
                [returnUrl: RETURN]) == HttpStatus.CONFLICT

        when:
        def needed = (api.call(admin, "GET", "/tenant/admin/mcp-connections") as List<Map>)[0].signIn as Map
        authServer.portalClient("portal-client", "portal-secret", needed.redirectUri as String)
        api.call(admin, "PUT", "/tenant/admin/mcp-connections/${created.id}",
                [name: "Fakeagent", baseUrl: agent.url(), clientId: "portal-client", clientSecret: "portal-secret"])

        then:
        needed.clientIdNeeded
        signIn(admin, created.id).endsWith("signIn=ok")

        cleanup:
        authServer.stop()
    }

    def "a card whose OAuth scheme has no authorization code flow says so; a card without a scheme needs nothing"() {
        given:
        def authServer = new FakeAuthorizationServer()
        agent.oauth = authServer
        agent.clientCredentialsOnly = true
        def tenant = api.tenant()
        allowA2a(tenant)
        def admin = api.tenantAdminToken(tenant.id)

        when:
        def created = api.call(admin, "POST", "/tenant/admin/mcp-connections", request()) as Map

        then:
        (created.toolsError as String).contains("offers no sign-in in the browser")
        created.signIn == null

        cleanup:
        authServer.stop()
    }

    static final String RETURN = "http://127.0.0.1:4300/app/software"
    static final String QUESTION = 'Prüfe R-1 [[call Fakeagent__ask {"question":"Ist R-1 bezahlt?","skill":"rechnungen"}]]'

    /** Signs in as the tenant admin's browser: the server's page, our callback, the page completing it - its outcome. */
    private String signIn(String admin, Object connectionId) {
        def started = api.call(admin, "POST", "/tenant/admin/mcp-connections/${connectionId}/sign-in", [returnUrl: RETURN]) as Map
        def browser = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
        def atServer = browser.send(java.net.http.HttpRequest.newBuilder(URI.create(started.authorizationUrl as String)).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding())
        def callback = browser.send(java.net.http.HttpRequest.newBuilder(URI.create(atServer.headers().firstValue("Location").get())).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding())
        api.completeSignIn(admin, "/tenant/admin/mcp-connections", callback.headers().firstValue("Location").get())
    }

    private Map connected() {
        def tenant = api.tenant()
        allowA2a(tenant)
        def admin = api.tenantAdminToken(tenant.id)
        agent.key = "agent-key-123456"
        def connection = api.call(admin, "POST", "/tenant/admin/mcp-connections", request() + [apiKey: "agent-key-123456"]) as Map
        assert connection.toolsError == null
        [tenant: tenant, admin: admin, connection: connection]
    }

    private Map request(String name = "Fakeagent") {
        [name: name, baseUrl: agent.url(), protocol: "A2A", apiKey: agent.key]
    }

    private void allowA2a(Map tenant) {
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, a2aAllowed: true])
    }

    private String offeredDescription(String name) {
        (fake.chats.last().toolSpecifications as List<dev.langchain4j.agent.tool.ToolSpecification>)
                .find { it.name() == name }.description()
    }

    private Map chatModel(Map tenant, String admin) {
        def chat = api.chatModel()
        api.allow(tenant.id, chat)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        chat
    }
}
