package de.alpdesk.controller.mcp

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * The MCP server's own reading of the company knowledge (roadmap point 37): the client's model searches with
 * search_knowledge and reads on with read_document - it finds exactly what ask_assistant finds, the company knowledge
 * and the MCP server's areas, and nothing of another tenant. No second model answers; only the query's embedding is
 * used.
 */
@MicronautTest(transactional = false)
class McpKnowledgeSpec extends Specification {

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

    def "search_knowledge returns the passages under their document's name and id - no model answers, the query's embedding is the usage"() {
        given:
        def setup = knowledgeTenant()
        def document = uploaded(setup.admin, "wartung.txt", "Die Wartung der Anlage ist jeden Montag um sieben Uhr.")
        def embeddingsBefore = embeddingCalls(setup.admin)

        when:
        def result = tool(setup.key, "search_knowledge", [query: "Wann ist die Wartung der Anlage?"])

        then:
        !result.isError
        result.content[0].text.startsWith("--- From \"wartung.txt\" (document ${document.id}) ---\n")
        result.content[0].text.contains("Die Wartung der Anlage ist jeden Montag um sieben Uhr.")
        fake.chats.isEmpty()
        embeddingCalls(setup.admin) == embeddingsBefore + 1
        (api.call(setup.admin, "GET", "/tenant/admin/usage") as List<Map>)*.kind == ["EMBEDDING"]
    }

    def "a search finds as many passages as the tenant's chat - its assistant settings"() {
        given:
        def setup = knowledgeTenant()
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", settings(3))
        (1..5).each { uploaded(setup.admin, "regel-${it}.txt", "Regel $it: die Wartung der Anlage wird protokolliert.") }

        when:
        def text = tool(setup.key, "search_knowledge", [query: "Wartung der Anlage protokolliert"]).content[0].text as String

        then:
        text.findAll(/--- From "/).size() == 3
    }

    def "nothing found, no query or no embedding model: the model is told so"() {
        given:
        def setup = knowledgeTenant()
        def withoutModel = api.tenant()

        when:
        def nothing = tool(setup.key, "search_knowledge", [query: "Urlaubsantrag"])
        def noQuery = tool(setup.key, "search_knowledge", [query: "  "])
        def noModel = tool(mcpKey(withoutModel), "search_knowledge", [query: "Wartung"])

        then:
        !nothing.isError
        nothing.content[0].text == 'No passage of the company\'s documents matches "Urlaubsantrag".'
        noQuery.isError
        noQuery.content[0].text == "The query is required."
        noModel.isError
        noModel.content[0].text.startsWith("The company has no searchable documents yet")
    }

    def "read_document reads a short document whole by its id"() {
        given:
        def setup = knowledgeTenant()
        def text = "Die Wartung der Anlage ist jeden Montag. Danach wird das Protokoll abgelegt."
        def document = uploaded(setup.admin, "wartung.txt", text)

        when:
        def result = tool(setup.key, "read_document", [document: document.id])

        then:
        !result.isError
        result.content[0].text.startsWith("=== Document \"wartung.txt\" (complete) ===\n")
        result.content[0].text.contains(text)
    }

    def "a long document: the search names where the passage starts, read_document reads a part from there and names where to read on"() {
        given:
        def setup = knowledgeTenant()
        def document = uploaded(setup.admin, "handbuch.txt", longHandbook())

        when:
        def found = tool(setup.key, "search_knowledge", [query: "Kalibrierung Sensor Zylinderkopf"]).content[0].text as String
        def offset = (found =~ /read on from offset (\d+) of (\d+)/)
        def part = tool(setup.key, "read_document", [document: document.id, offset: offset[0][1] as int]).content[0].text as String
        def start = tool(setup.key, "read_document", [document: document.id]).content[0].text as String

        then:
        found.startsWith("--- From \"handbuch.txt\" (document ${document.id}, one passage of ")
        found.contains("call read_document with the document's id")
        (offset[0][1] as int) > 0
        part.startsWith("=== Document \"handbuch.txt\", characters ${offset[0][1]} to ${(offset[0][1] as int) + 5000} of ${offset[0][2]} ===")
        part.contains("Kalibrierung des Sensors am Zylinderkopf")
        part.contains("read on with offset ${(offset[0][1] as int) + 5000}")
        start.startsWith("=== Document \"handbuch.txt\", characters 0 to 5000 of ")
    }

    def "an offset beyond the end is answered, a negative one or no id is a tool error"() {
        given:
        def setup = knowledgeTenant()
        def document = uploaded(setup.admin, "wartung.txt", "Die Wartung ist jeden Montag.")

        expect:
        (tool(setup.key, "read_document", [document: document.id, offset: 100_000]).content[0].text as String)
                .matches(/"wartung\.txt" \(document ${document.id}\) has only \d+ characters\./)
        tool(setup.key, "read_document", [document: document.id, offset: -1]).isError
        tool(setup.key, "read_document", [document: document.id, offset: -1]).content[0].text == "The offset must not be negative."
        tool(setup.key, "read_document", [:]).isError
    }

    def "only the company knowledge and the key's areas - another key of the tenant with the area finds its document"() {
        given:
        def setup = knowledgeTenant()
        def area = api.call(setup.admin, "POST", "/tenant/admin/knowledge-areas", [name: "Werkstatt"]) as Map
        def document = uploaded(setup.admin, "werkstatt.txt", "Die Drehbank in der Werkstatt wird freitags geölt.")
        api.call(setup.admin, "PUT", "/tenant/admin/documents/${document.id}/area", [areaId: area.id])

        when:
        def searchedBefore = tool(setup.key, "search_knowledge", [query: "Drehbank Werkstatt geölt"]).content[0].text as String
        def readBefore = tool(setup.key, "read_document", [document: document.id])
        def workshopKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys",
                [name: "Werkstatt", areaIds: [area.id]]) as Map).plainKey as String
        def searchedAfter = tool(workshopKey, "search_knowledge", [query: "Drehbank Werkstatt geölt"]).content[0].text as String
        def readAfter = tool(workshopKey, "read_document", [document: document.id])

        then:
        !searchedBefore.contains("werkstatt.txt")
        readBefore.isError
        readBefore.content[0].text == "Document ${document.id} is not found - it may have been deleted, moved or be updating: search again."
        searchedAfter.contains("--- From \"werkstatt.txt\" (document ${document.id}) ---")
        !readAfter.isError
    }

    def "another tenant's document, an unknown or a deleted one is not found - the same answer for all"() {
        given:
        def setup = knowledgeTenant()
        def other = knowledgeTenant()
        def foreign = uploaded(other.admin, "fremd.txt", "Die Preise der anderen Firma sind geheim.")
        def deleted = uploaded(setup.admin, "alt.txt", "Die alte Regel galt bis Juni.")
        api.status(setup.admin, "DELETE", "/tenant/admin/documents/${deleted.id}")

        expect:
        [foreign.id, deleted.id, 999_999_999].every { id ->
            def result = tool(setup.key, "read_document", [document: id])
            result.isError && result.content[0].text == "Document $id is not found - it may have been deleted, moved or be updating: search again."
        }
        !(tool(setup.key, "search_knowledge", [query: "Preise der anderen Firma geheim"]).content[0].text as String).contains("fremd.txt")
    }

    def "a document waiting to be embedded again is not found until it is ready - never its old text"() {
        given:
        def setup = knowledgeTenant()
        def document = uploaded(setup.admin, "regel.txt", "Die Regel für Urlaubsanträge gilt ab März.")
        fake.embeddingDelay = 2_000

        when:
        api.call(setup.admin, "POST", "/tenant/admin/documents/embed-again")
        def waiting = tool(setup.key, "read_document", [document: document.id])
        fake.embeddingDelay = 0
        api.awaitDocument(setup.admin, document.id)
        def ready = tool(setup.key, "read_document", [document: document.id])

        then:
        waiting.isError
        waiting.content[0].text == "Document ${document.id} is not found - it may have been deleted, moved or be updating: search again."
        !ready.isError
        (ready.content[0].text as String).contains("Urlaubsanträge")
    }

    /** A tenant with a chat and an embedding model, its keys and its MCP key: its tenant admin, the key. */
    private Map knowledgeTenant() {
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = api.chatModel()
        def embedding = api.embeddingModel()
        api.allow(tenant.id, chat, embedding)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        [tenant: tenant, admin: admin, key: mcpKey(tenant)]
    }

    private int embeddingCalls(String admin) {
        ((api.call(admin, "GET", "/tenant/admin/usage") as List<Map>).find { it.kind == "EMBEDDING" }?.calls ?: 0) as int
    }

    private Map uploaded(String admin, String name, String text) {
        api.awaitDocument(admin, api.upload(admin, name, text).id)
    }

    /** About 30,000 characters of a manual, one rule in its middle the search finds. */
    private static String longHandbook() {
        def filler = (1..120).collect { "Abschnitt $it beschreibt allgemeine Hinweise zur Bedienung und Pflege der Maschine im Alltag." }
        def middle = "Die Kalibrierung des Sensors am Zylinderkopf erfolgt mit dem Prüfgerät Muster-K7 nach jedem Ölwechsel."
        (filler[0..<60] + [middle] + filler[60..<120]).join("\n\n") * 2
    }

    private static Map settings(int searchResults) {
        [instructions: "", answerSource: "DOCUMENTS_ONLY", creativity: "MEDIUM", reasoning: "LOW",
         searchResults: searchResults, defaultModelId: null]
    }

    private String mcpKey(Map tenant) {
        (api.call(api.tenantAdminToken(tenant.id), "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map).plainKey
    }

    /** A tool's result. */
    private Map tool(String key, String name, Map arguments) {
        Map message = [jsonrpc: "2.0", id: UUID.randomUUID().toString(), method: "tools/call",
                       params : [name: name, arguments: arguments]]
        try {
            Map body = client.toBlocking().retrieve(HttpRequest.POST("/mcp", message).bearerAuth(key), Map) as Map
            // Arguments against the schema the SDK refuses as a JSON-RPC error of its own.
            body.result as Map ?: [isError: true, content: [[text: body.error?.message]]]
        } catch (HttpClientResponseException e) {
            [isError: true, content: [[text: e.response.getBody(Map).orElse([:]).toString()]]]
        }
    }
}
