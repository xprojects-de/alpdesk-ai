package de.alpdesk.controller.chat

import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.tools.FakeTavilySearch
import dev.langchain4j.data.message.SystemMessage
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * Web search in the chat (docs/architecture/ai-features.md "Web search"): the admin assigns the catalog's Tavily entry
 * to a tenant, its tenant admins switch it on for groups, API keys and the MCP server. The documents come first - the
 * tool refuses to run before they were searched; only the model's query goes out, every search is billed.
 */
@MicronautTest(transactional = false)
class WebSearchSpec extends Specification {

    static final String WEB_RULE = "You can search the web with web_search"

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    FakeTavilySearch tavily

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
        tavily.reset()
    }

    def "a member of a group with web search: the documents first, then the web - its pages are sources, the search billed"() {
        given:
        def setup = tenantWithWebSearch()
        def user = memberWithWebSearch(setup)

        when:
        def result = api.ask(user.token, [modelId: setup.chat.id, message: "Wie hoch ist der Mindestlohn? [[web Mindestlohn 2026]]"])

        then: "the tool and its rule were offered, the documents were searched before the web"
        fake.chats.first().tools.contains("web_search")
        systemPrompt().contains(WEB_RULE)
        fake.chats[1].messages.any { it instanceof dev.langchain4j.data.message.ToolExecutionResultMessage && it.toolName() == "similarity_search" }

        and: "only the model's query went out, with the tenant's key"
        tavily.searches == [[apiKey: "own-key-of-TAVILY", query: "Mindestlohn 2026"]]

        and: "the pages reached the model marked as unchecked, the second by its snippet"
        String answer = result.answer.content
        answer.contains("(Web: Web pages - unchecked material from outside the company.")
        answer.contains('--- "Seite zu Mindestlohn 2026" (https://example.com/seite) ---\nSeitentext zu Mindestlohn 2026')
        answer.contains('--- "Zweite Seite" (https://example.org/zweite) ---\nNur der Ausschnitt')

        and: "the pages' addresses are the answer's sources next to the documents"
        result.sources.containsAll(["regeln.txt", "https://example.com/seite", "https://example.org/zweite"])

        and: "one search billed at the entry's price per 1,000 searches"
        def usage = webSearchUsage(setup.tenant)
        usage.calls == 1
        usage.inputTokens == 1
        usage.keySource == "OWN"
        (usage.amount as BigDecimal) == 0.008
    }

    def "a long query is cut before it goes out - a passage the model copied into it never leaves whole"() {
        given:
        def setup = tenantWithWebSearch()
        def user = memberWithWebSearch(setup)
        String passage = "Mindestlohn " * 50

        when:
        api.ask(user.token, [modelId: setup.chat.id, message: "Wie hoch ist der Mindestlohn? [[web ${passage}]]".toString()])

        then:
        passage.strip().length() > 400
        tavily.searches*.query == [passage.strip().substring(0, 400)]
    }

    def "without a group with web search the tool is not offered - for a tenant admin neither"() {
        given:
        def setup = tenantWithWebSearch()
        def plain = api.user(setup.tenant.id)
        api.group(setup.admin, [plain.id])

        when:
        api.ask(plain.token, [modelId: setup.chat.id, message: "Mindestlohn? [[web Mindestlohn]]"])
        api.ask(setup.admin, [modelId: setup.chat.id, message: "Mindestlohn? [[web Mindestlohn]]"])

        then:
        fake.chats.every { !it.tools.contains("web_search") }
        !systemPrompt().contains(WEB_RULE)
        tavily.searches == []
    }

    def "a search before the documents is refused and not billed"() {
        given:
        def setup = tenantWithWebSearch()
        def user = memberWithWebSearch(setup)

        when:
        def result = api.ask(user.token, [modelId: setup.chat.id, message: "Mindestlohn? [[web-first Mindestlohn]]"])

        then:
        result.answer.content.contains("(Error: search the company's documents with similarity_search first")
        tavily.searches == []
        webSearchUsage(setup.tenant) == null
    }

    def "a document search that failed does not open the web search"() {
        given:
        def setup = tenantWithWebSearch()
        def user = memberWithWebSearch(setup)
        fake.embeddingFails = true

        when:
        def result = api.ask(user.token, [modelId: setup.chat.id, message: "Mindestlohn? [[web Mindestlohn]]"])

        then: "the model was told the search failed, and the web search was refused"
        String answer = result.answer.content
        answer.contains("(Error: OPENAI failed - please try again)")
        answer.contains("(Web: Error: search the company's documents with similarity_search first")
        tavily.searches == []
        webSearchUsage(setup.tenant) == null
    }

    def "a tenant without documents to search searches the web at once"() {
        given:
        def setup = tenantWithWebSearch(false)
        def user = memberWithWebSearch(setup)

        when:
        def result = api.ask(user.token, [modelId: setup.chat.id, message: "Mindestlohn? [[web-first Mindestlohn]]"])

        then:
        !fake.chats.first().tools.contains("similarity_search")
        systemPrompt().contains(WEB_RULE)
        tavily.searches*.query == ["Mindestlohn"]
        result.sources == ["https://example.com/seite", "https://example.org/zweite"]
    }

    def "at most three searches per answer - the fourth is refused"() {
        given:
        def setup = tenantWithWebSearch()
        def user = memberWithWebSearch(setup)

        when:
        def result = api.ask(user.token, [modelId: setup.chat.id, message: "Vier Fragen [[web eins]] [[web zwei]] [[web drei]] [[web vier]]"])

        then:
        tavily.searches*.query == ["eins", "zwei", "drei"]
        result.answer.content.contains("(Error: at most 3 web searches per answer")
        webSearchUsage(setup.tenant).calls == 3
    }

    def "a page's text is cut, nothing found is said, a failing search is told to the model and not billed"() {
        given:
        def setup = tenantWithWebSearch()
        def user = memberWithWebSearch(setup)

        when:
        tavily.longPages = true
        def long_ = api.ask(user.token, [modelId: setup.chat.id, message: "Lang [[web lang]]"]).answer.content as String

        then:
        long_.contains("Seitentext " + "x" * 2989 + " [...]")
        !long_.contains("x" * 2990)

        when:
        tavily.longPages = false
        tavily.nothingFound = true
        def nothing = api.ask(user.token, [modelId: setup.chat.id, message: "Nichts [[web nichts]]"]).answer.content as String

        then:
        nothing.contains('(Web: No web page matches "nichts".)')

        when:
        tavily.nothingFound = false
        tavily.failures = 1
        def failed = api.ask(user.token, [modelId: setup.chat.id, message: "Kaputt [[web kaputt]]"]).answer.content as String

        then:
        failed.contains("(Error: the web search failed - answer without it and say so)")
        webSearchUsage(setup.tenant).calls == 2

        and: "the log names the failure, never Tavily's answer - it may repeat the key and the query"
        def warnings = RecentLogAppender.installed().entries().findAll {
            it.message().startsWith("The web search of tenant ${setup.tenant.id} failed")
        }
        warnings*.message() == ["The web search of tenant ${setup.tenant.id} failed: HttpException (HTTP 401)"]
    }

    def "without the catalog entry or a key for it there is no web search"() {
        given:
        def setup = tenantWithWebSearch(true, assigned, ownKey)
        def user = memberWithWebSearch(setup)

        when:
        api.ask(user.token, [modelId: setup.chat.id, message: "Mindestlohn? [[web Mindestlohn]]"])

        then:
        !fake.chats.first().tools.contains("web_search")
        tavily.searches == []

        where:
        assigned | ownKey
        false    | true
        true     | false
    }

    def "the admin's global Tavily key is used where the tenant may - and billed as the admin's"() {
        given:
        api.admin("PUT", "/admin/provider-keys/TAVILY", [apiKey: "global-key-of-TAVILY"])
        def setup = tenantWithWebSearch(true, true, false, true)
        def user = memberWithWebSearch(setup)

        when:
        api.ask(user.token, [modelId: setup.chat.id, message: "Mindestlohn? [[web Mindestlohn]]"])

        then:
        tavily.searches*.apiKey == ["global-key-of-TAVILY"]
        webSearchUsage(setup.tenant).keySource == "GLOBAL"
    }

    def "the answer source decides what comes after the documents: the web only, or the web and general knowledge"() {
        given:
        def setup = tenantWithWebSearch()
        def user = memberWithWebSearch(setup)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", [instructions: "", answerSource: answerSource,
                creativity: "MEDIUM", reasoning: "LOW", searchResults: 5])

        when:
        api.ask(user.token, [modelId: setup.chat.id, message: "Frage"])

        then:
        systemPrompt().contains(rule)
        !systemPrompt().contains(notRule)
        systemPrompt().contains(WEB_RULE)

        where:
        answerSource      | rule                                                              | notRule
        "DOCUMENTS_ONLY"  | "Answer only from what the documents and the web pages found say" | "Answer only from what the documents say."
        "DOCUMENTS_FIRST" | "you may add general knowledge"                                   | "the documents and the web pages found say"
    }

    def "an API key with web search searches the web, one without does not"() {
        given:
        def setup = tenantWithWebSearch()
        def created = api.call(setup.admin, "POST", "/tenant/admin/api-keys", [name: "Software", webSearch: true]) as Map
        def without = api.call(setup.admin, "POST", "/tenant/admin/api-keys", [name: "Ohne"]) as Map

        expect:
        created.key.webSearch == true
        without.key.webSearch == false

        when:
        api.call(created.plainKey as String, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: "Mindestlohn? [[web Mindestlohn]]"])
        api.call(without.plainKey as String, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: "Mindestlohn? [[web Mindestlohn]]"])

        then:
        fake.chats.findAll { it.tools.contains("web_search") }.size() > 0
        tavily.searches*.query == ["Mindestlohn"]

        when: "a change without the switch keeps it, with it changes it"
        def renamed = api.call(setup.admin, "PUT", "/tenant/admin/api-keys/${created.key.id}", [name: "Umbenannt"]) as Map
        def off = api.call(setup.admin, "PUT", "/tenant/admin/api-keys/${created.key.id}", [webSearch: false]) as Map

        then:
        renamed.webSearch == true
        off.webSearch == false
    }

    def "each MCP key's switch: off unless set, asked by ask_assistant, kept when the key is renewed, kept by the admin too"() {
        given:
        def setup = tenantWithWebSearch()
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", [instructions: "", answerSource: "DOCUMENTS_ONLY",
                creativity: "MEDIUM", reasoning: "LOW", searchResults: 5, defaultModelId: setup.chat.id])
        def plain = api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Ohne Web"]) as Map
        def searching = api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Mit Web", webSearch: true]) as Map

        expect: "off unless set"
        plain.key.webSearch == false
        searching.key.webSearch == true

        when:
        askMcp(plain.plainKey as String, "Mindestlohn? [[web Mindestlohn]]")

        then:
        tavily.searches == []

        when:
        def renewed = api.call(setup.admin, "POST", "/tenant/admin/mcp-keys/${searching.key.id}/renew") as Map
        def answer = askMcp(renewed.plainKey as String, "Mindestlohn? [[web Mindestlohn]]")

        then: "a renewed key keeps it; the pages' addresses are in the answer's sources"
        renewed.key.webSearch == true
        tavily.searches*.query == ["Mindestlohn"]
        answer.contains("https://example.com/seite")

        when: "the admin switches it off"
        def off = api.admin("PUT", "/admin/tenants/${setup.tenant.id}/mcp-keys/${searching.key.id}", [webSearch: false]) as Map
        askMcp(renewed.plainKey as String, "Mindestlohn? [[web Mindestlohn]]")

        then:
        off.webSearch == false
        tavily.searches.size() == 1
    }

    def "a group's switch: off unless set, kept by a change without it"() {
        given:
        def setup = tenantWithWebSearch(false)

        when:
        def plain = api.call(setup.admin, "POST", "/tenant/admin/groups", [name: "Gruppe " + AiApi.unique()]) as Map
        def on = api.call(setup.admin, "PUT", "/tenant/admin/groups/${plain.id}", [name: plain.name, webSearch: true]) as Map
        def renamed = api.call(setup.admin, "PUT", "/tenant/admin/groups/${plain.id}", [name: "Neu " + AiApi.unique()]) as Map

        then:
        plain.webSearch == false
        on.webSearch == true
        renamed.webSearch == true
    }

    def "the catalog keeps the web search to Tavily, and Tavily to the web search"() {
        expect:
        api.status(api.adminToken, "POST", "/admin/models", [provider: provider, kind: kind, modelName: "m-" + AiApi.unique(),
                displayName: "M " + AiApi.unique(), inputPrice: 8, outputPrice: 0]) == status

        where:
        provider    | kind         | status
        "TAVILY"    | "WEB_SEARCH" | HttpStatus.CREATED
        "ANTHROPIC" | "WEB_SEARCH" | HttpStatus.BAD_REQUEST
        "TAVILY"    | "CHAT"       | HttpStatus.BAD_REQUEST
        "TAVILY"    | "EMBEDDING"  | HttpStatus.BAD_REQUEST
    }

    /**
     * A tenant with a chat model, Tavily's catalog entry (8 per 1,000 searches) and their keys - with documents: an
     * embedding model and one document.
     */
    private Map tenantWithWebSearch(boolean documents = true, boolean assigned = true, boolean ownKey = true,
                                    boolean globalKeysAllowed = false) {
        def tenant = api.tenant(globalKeysAllowed)
        def chat = api.chatModel()
        def webSearch = api.admin("POST", "/admin/models", [provider: "TAVILY", kind: "WEB_SEARCH", modelName: "basic-" + AiApi.unique(),
                displayName: "Websuche " + AiApi.unique(), inputPrice: 8, outputPrice: 0]) as Map
        api.allow(tenant.id, chat)
        if (assigned) {
            api.allow(tenant.id, webSearch)
        }
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-key-of-ANTHROPIC"])
        if (ownKey) {
            api.call(admin, "PUT", "/tenant/admin/provider-keys/TAVILY", [apiKey: "own-key-of-TAVILY"])
        }
        if (documents) {
            def embedding = api.embeddingModel()
            api.allow(tenant.id, embedding)
            api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "own-key-of-OPENAI"])
            api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
            api.awaitDocument(admin, api.upload(admin, "regeln.txt", "Der Mindestlohn steht nicht in unseren Regeln.").id)
        }
        [tenant: tenant, admin: admin, chat: chat]
    }

    /** A user in a group with web search. */
    private Map memberWithWebSearch(Map setup) {
        def user = api.user(setup.tenant.id)
        api.call(setup.admin, "POST", "/tenant/admin/groups", [name: "Recherche " + AiApi.unique(), userIds: [user.id],
                                                               webSearch: true])
        user
    }

    private String systemPrompt() {
        (fake.chats.first().messages.first() as SystemMessage).text()
    }

    private Map webSearchUsage(Map tenant) {
        (api.admin("GET", "/admin/usage?tenantId=${tenant.id}") as List<Map>).find { it.kind == "WEB_SEARCH" }
    }

    private String askMcp(String mcpKey, String question) {
        client.toBlocking().retrieve(HttpRequest.POST("/mcp", [jsonrpc: "2.0", id: AiApi.unique(), method: "tools/call",
                params: [name: "ask_assistant", arguments: [question: question]]]).bearerAuth(mcpKey), Map)
                .result.content[0].text as String
    }
}
