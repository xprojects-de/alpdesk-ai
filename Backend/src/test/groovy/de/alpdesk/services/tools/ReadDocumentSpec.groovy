package de.alpdesk.services.tools

import de.alpdesk.repositories.ai.AiModelRepository
import de.alpdesk.repositories.document.DocumentRepository
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.document.KnowledgeScope
import de.alpdesk.services.tenant.TenantDatabases
import dev.langchain4j.agent.tool.ToolExecutionRequest
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * read_document (docs/architecture/ai-features.md "Reading on in a document"): a short document comes whole, a long one
 * around the passage found and on from an offset, within a limit per answer - and only documents the search found in
 * this answer, still in the asker's reach.
 */
@MicronautTest(transactional = false)
class ReadDocumentSpec extends Specification {

    static final String GUIDE = """Einen Teststep schreiben

Regeln: Jeder Teststep erbt von TestStep und gibt StepResult zurück, er wirft nie eine Exception.

Beispiel: class LoginStep extends TestStep - execute liefert StepResult.ok() oder StepResult.failed(Grund)."""
    static final String MARKER = "Der Prüfdruck des Kessels beträgt genau 4,5 bar."

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    Toolbox toolbox

    @Inject
    TenantRepository tenantEntities

    @Inject
    TenantDatabases databases

    @Inject
    AiModelRepository models

    @Inject
    DocumentRepository documents

    @Shared
    AiApi api

    /** The tenants of a feature - deleted afterwards, which closes their pools: the suite shares one PostgreSQL. */
    List<Object> tenants = []

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def cleanup() {
        tenants.each { api.status(api.adminToken, "DELETE", "/admin/tenants/${it}") }
    }

    def "a short document is read whole"() {
        given:
        def setup = knowledge()
        api.awaitDocument(setup.admin, api.upload(setup.admin, "teststep.md", GUIDE).id)
        def context = context(setup)
        def found = search(context, "Teststep Regeln").text()

        when:
        def read = read(context, [document: "teststep.md"])

        then: "one passage is the whole document - nothing says to read on"
        found.startsWith('--- From "teststep.md" ---\n')
        !found.contains(SimilaritySearchTool.READ_ON_HINT)
        read.text() == '=== Document "teststep.md" (complete) ===\n' + GUIDE
        context.sources().contains("teststep.md")
        context.documentCharactersRead() == GUIDE.length()
    }

    def "two documents of the same name are told apart, and each is read as itself"() {
        given:
        def setup = knowledge()
        def older = "Preise 2025: Die Stunde kostet 80 Euro."
        def newer = "Preise 2026: Die Stunde kostet 95 Euro."
        [older, newer].each { api.awaitDocument(setup.admin, api.upload(setup.admin, "preise.txt", it).id) }
        def context = context(setup)

        when:
        def found = search(context, "Preise Stunde kostet Euro").text()

        then:
        found.contains('--- From "preise.txt" ---')
        found.contains('--- From "preise.txt (2)" ---')
        ["preise.txt", "preise.txt (2)"].collect { read(context, [document: it]).text().readLines().last() } as Set ==
                [older, newer] as Set
    }

    def "a long document is read around the passage found, and on from the offset it names"() {
        given:
        def setup = knowledge()
        def text = manual()
        api.awaitDocument(setup.admin, api.upload(setup.admin, "handbuch.txt", text).id)
        def context = context(setup)
        def found = search(context, "Prüfdruck Kessel").text()

        expect: "the search says the passage is part of a longer document, and to read on"
        found.contains('--- From "handbuch.txt" (one passage of ')
        found.endsWith(SimilaritySearchTool.READ_ON_HINT)

        when:
        def first = read(context, [document: "handbuch.txt"]).text()
        def start = (first =~ /characters (\d+) to (\d+) of (\d+)/)[0][1] as int
        def end = (first =~ /characters (\d+) to (\d+) of (\d+)/)[0][2] as int

        then: "the passage with about two of its size before it"
        first.contains(MARKER)
        start > 0
        text.indexOf(MARKER) - start <= ReadDocumentTool.BEFORE_PASSAGE + 1_000
        end - start == ReadDocumentTool.PART
        first.endsWith("[earlier text: offset ${start - ReadDocumentTool.PART}; read on with offset ${end}]")

        when:
        def next = read(context, [document: "handbuch.txt", offset: end]).text()

        then:
        next.startsWith("=== Document \"handbuch.txt\", characters ${end} to ${end + ReadDocumentTool.PART} of ${text.length()} ===\n")
        next.contains(text.substring(end, end + 100))

        when: "beyond its end"
        def beyond = read(context, [document: "handbuch.txt", offset: text.length()]).text()

        then:
        beyond == "\"handbuch.txt\" has only ${text.length()} characters."
    }

    def "a model the application searches for gets no hint to read on - it cannot"() {
        given:
        def setup = knowledge()
        api.awaitDocument(setup.admin, api.upload(setup.admin, "handbuch.txt", manual()).id)
        def withoutTools = api.chatModel("ANTHROPIC", [callsTools: false])

        when:
        def found = search(context(setup + [chat: withoutTools]), "Prüfdruck Kessel").text()

        then:
        found.contains('--- From "handbuch.txt" (one passage of ')
        !found.contains(SimilaritySearchTool.READ_ON_HINT)
    }

    def "one answer reads at most its limit - the last part is cut, then reading is refused"() {
        given:
        def setup = knowledge()
        def guide = GUIDE + "\n\n" + ("Weitere Hinweise zum Teststep. " * 250)
        api.awaitDocument(setup.admin, api.upload(setup.admin, "handbuch.txt", manual()).id)
        api.awaitDocument(setup.admin, api.upload(setup.admin, "teststep.md", guide).id)
        def context = context(setup)
        search(context, "Prüfdruck Kessel")
        search(context, "Teststep Regeln")

        when: "five parts of the manual"
        (0..4).each { read(context, [document: "handbuch.txt", offset: it * ReadDocumentTool.PART]) }
        def cut = read(context, [document: "teststep.md"]).text()

        then: "the guide is longer than what is left"
        guide.length() > ReadDocumentTool.PART
        cut.endsWith("[The reading limit of this answer is reached - answer with what you have read]")
        context.documentCharactersRead() == ReadDocumentTool.ANSWER_LIMIT

        expect:
        read(context, [document: "teststep.md"]).text() ==
                "Error: the reading limit of this answer is reached - answer with what you have read"
    }

    def "only a document the search found in this answer is read: #reason"() {
        given:
        def setup = knowledge()
        api.awaitDocument(setup.admin, api.upload(setup.admin, "teststep.md", GUIDE).id)
        def context = context(setup)
        if (searched) {
            search(context, "Teststep Regeln")
        }

        expect:
        read(context, [document: name]).text().startsWith(
                "Error: read_document reads only documents similarity_search found in this answer")

        where:
        reason                   | searched | name
        "nothing searched yet"   | false    | "teststep.md"
        "a name it did not find" | true     | "geheim.md"
    }

    def "a document #change after it was found is no longer read"() {
        given:
        def setup = knowledge()
        def document = api.upload(setup.admin, "teststep.md", GUIDE)
        api.awaitDocument(setup.admin, document.id)
        def area = api.call(setup.admin, "POST", "/tenant/admin/knowledge-areas", [name: "Entwicklung"]) as Map
        def context = context(setup, KnowledgeScope.companyKnowledgeAnd([]))
        search(context, "Teststep Regeln")

        when:
        if (change == "deleted") {
            api.status(setup.admin, "DELETE", "/tenant/admin/documents/${document.id}")
        } else {
            api.call(setup.admin, "PUT", "/tenant/admin/documents/${document.id}/area", [areaId: area.id])
        }

        then:
        read(context, [document: "teststep.md"]).text() ==
                'Error: "teststep.md" can no longer be read - it was deleted, moved or is being updated'
        context.documentCharactersRead() == 0

        where:
        change << ["deleted", "moved into an area the asker does not find"]
    }

    def "an area's document is read by its group's members only - another user finds and reads the company knowledge"() {
        given:
        def setup = knowledge()
        def area = api.call(setup.admin, "POST", "/tenant/admin/knowledge-areas", [name: "Entwicklung"]) as Map
        api.awaitDocument(setup.admin, uploadInto(setup.admin, "teststep.md", GUIDE, area.id).id)
        api.awaitDocument(setup.admin, api.upload(setup.admin, "allgemein.md", "Teststep allgemein: frag die Entwicklung").id)
        def developers = api.group(setup.admin, [], [area.id])
        def developer = api.call(setup.admin, "POST", "/tenant/admin/users", [username: "dev-" + AiApi.unique(),
                displayName: "Maria", password: AiApi.USER_PASSWORD, groupIds: [developers.id]]) as Map

        expect:
        ask(login(developer.username as String), setup).contains("(complete) ===\n" + GUIDE)
        !ask(api.userToken(setup.tenant.id), setup).contains("Regeln: Jeder Teststep")
    }

    def "the chat, the client software and the MCP server read on alike - each in its own tenant"() {
        given:
        def setup = knowledge()
        api.awaitDocument(setup.admin, api.upload(setup.admin, "teststep.md", GUIDE).id)
        def other = knowledge()
        api.awaitDocument(other.admin, api.upload(other.admin, "teststep.md", "Teststep bei der anderen Firma: alles anders").id)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", [instructions: "", answerSource: "DOCUMENTS_ONLY",
                creativity: "MEDIUM", reasoning: "LOW", searchResults: 5, defaultModelId: setup.chat.id])
        def apiKey = (api.call(setup.admin, "POST", "/tenant/admin/api-keys", [name: "Software"]) as Map).plainKey as String
        def mcpKey = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map).plainKey as String
        def question = "Wie schreibe ich einen Teststep? [[read-document]]"

        when:
        def chat = api.ask(setup.admin, [modelId: setup.chat.id, message: question]).answer.content as String
        def software = api.call(apiKey, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: question]).answer.content as String
        def mcp = client.toBlocking().retrieve(HttpRequest.POST("/mcp", [jsonrpc: "2.0", id: AiApi.unique(),
                method: "tools/call", params: [name: "ask_assistant", arguments: [question: question]]]).bearerAuth(mcpKey), Map)
                .result.content[0].text as String

        then:
        [chat, software, mcp].every { it.contains('(Dokument: === Document "teststep.md" (complete) ===\n' + GUIDE) }
        [chat, software, mcp].every { !it.contains("alles anders") }
        fake.chats.first().messages.first().text().contains("read on in that document with read_document")
    }

    /** A tenant with a chat and an embedding model and their keys: [tenant, admin (token), chat (model)]. */
    private Map knowledge() {
        def tenant = api.tenant()
        tenants << tenant.id
        def admin = api.tenantAdminToken(tenant.id)
        def embedding = api.embeddingModel()
        def chat = api.chatModel()
        api.allow(tenant.id, embedding, chat)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        [tenant: tenant, admin: admin, chat: chat]
    }

    /** About 60,000 characters of paragraphs that do not answer, MARKER in the middle. */
    private static String manual() {
        (1..70).collect { it == 35 ? MARKER : "Abschnitt ${it}: " + ("Allgemeine Hinweise zur Pflege des Geräts. " * 20).strip() }
                .join("\n\n")
    }

    private ToolContext context(Map setup, KnowledgeScope scope = KnowledgeScope.everything()) {
        def tenant = tenantEntities.findById(setup.tenant.id as Long).get()
        new ToolContext(tenant, ToolContext.Asker.USER, models.findById(setup.chat.id as Long).get(), null, scope,
                databases.dataSource(tenant.databaseName()), null, false, 5, ToolContext.Files.NONE)
    }

    private ToolResult search(ToolContext context, String query) {
        request(context, "similarity_search", [query: query])
    }

    private ToolResult read(ToolContext context, Map arguments) {
        request(context, "read_document", arguments)
    }

    private ToolResult request(ToolContext context, String name, Map arguments) {
        toolbox.execute(context, ToolExecutionRequest.builder().id("1").name(name)
                .arguments(io.micronaut.serde.ObjectMapper.getDefault().writeValueAsString(arguments)).build())
    }

    private String ask(String token, Map setup) {
        api.ask(token, [modelId: setup.chat.id, message: "Wie schreibe ich einen Teststep? [[read-document]]"]).answer.content as String
    }

    private String login(String username) {
        client.toBlocking().retrieve(HttpRequest.POST("/login", [username: username, password: AiApi.USER_PASSWORD]), Map).access_token
    }

    private Map uploadInto(String token, String fileName, String text, Object areaId) {
        def body = MultipartBody.builder().addPart("file", fileName, MediaType.TEXT_PLAIN_TYPE, text.getBytes("UTF-8"))
                .addPart("areaId", areaId.toString()).build()
        client.toBlocking().retrieve(HttpRequest.POST("/tenant/admin/documents", body)
                .contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(token), Map)
    }
}
