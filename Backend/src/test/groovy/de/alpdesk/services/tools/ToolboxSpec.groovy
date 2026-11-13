package de.alpdesk.services.tools

import de.alpdesk.entities.mcp.McpConnection
import de.alpdesk.entities.mcp.McpConnectionProtocol
import de.alpdesk.entities.mcp.McpTool
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.chat.FileRenderer
import de.alpdesk.services.chat.OutputFormat
import de.alpdesk.services.document.KnowledgeScope
import de.alpdesk.services.mcp.McpTools
import de.alpdesk.services.sandbox.FakeSandboxes
import de.alpdesk.services.tenant.TenantDatabases
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant

/** The tools as the model calls them: which ones a question gets, what they answer, how a wrong call is answered. */
@MicronautTest(transactional = false)
class ToolboxSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    Toolbox toolbox

    @Inject
    TenantRepository tenants

    @Inject
    TenantDatabases databases

    @Inject
    de.alpdesk.repositories.ai.AiModelRepository models

    @Inject
    FakeSandboxes sandboxes

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
    }

    def "a question gets the search and read_document only with a knowledge base, read_file only with files"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)

        expect:
        names(context(tenant, null, false)) == ["create_file"]
        names(context(tenant, null, true)).toSet() == ["create_file", "read_file"].toSet()

        when:
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])

        then:
        names(context(tenant, null, false)).toSet() == ["create_file", "similarity_search", "read_document"].toSet()
    }

    def "read_file returns a long text in parts"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)
        def text = ("a" * 100_000) + ("b" * 50)
        def uploaded = api.chatUpload(token, ["lang.txt": text.getBytes()])
        def context = context(tenant, uploaded.conversation.id, true)
        def fileId = uploaded.files[0].id

        when:
        def first = call(context, "read_file", [file_id: fileId, mode: "TEXT"])
        def second = call(context, "read_file", [file_id: fileId, mode: "TEXT", offset: 100_000])

        then:
        first.text().startsWith('=== File "lang.txt" ===\n' + "a" * 10)
        first.text().endsWith("[Characters 0 to 100000 of 100050 - read on with offset 100000]")
        second.text() == '=== File "lang.txt" ===\n' + "b" * 50
        first.attachments().isEmpty()
    }

    def "read_file renders the pages of a PDF with text as images on demand, at most ten per call"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)
        def pdf = new FileRenderer().render(OutputFormat.PDF, (1..12).collect { "# Seite ${it}\n\n<div style=\"page-break-after: always\"></div>\n" }.join("\n")).content()
        def uploaded = api.chatUpload(token, ["seiten.pdf": pdf])
        def context = context(tenant, uploaded.conversation.id, true)

        when:
        def all = call(context, "read_file", [file_id: uploaded.files[0].id, mode: "PAGES"])
        def two = call(context, "read_file", [file_id: uploaded.files[0].id, mode: "PAGES", first_page: 11, last_page: 30])

        then:
        all.attachments().size() == 10
        all.attachments()[0].label() == 'Page 1 of "seiten.pdf"'
        all.attachments()*.mimeType().toSet() == ["image/jpeg"].toSet()
        all.text().startsWith('Pages 1 to 10 of ')
        two.attachments()*.label() == ['Page 11 of "seiten.pdf"', 'Page 12 of "seiten.pdf"']
        new String(two.attachments()[0].content(), 6, 4) == "JFIF"

        when: "the model reads it as itself"
        def original = call(context, "read_file", [file_id: uploaded.files[0].id, mode: "ORIGINAL"])

        then:
        original.text() == 'The original PDF "seiten.pdf" follows the tool results.'
        original.attachments().size() == 1
        original.attachments()[0].mimeType() == "application/pdf"
        original.attachments()[0].content() == pdf
    }

    def "a tool offered for the question but gone meanwhile - the sandbox went down - is answered, not denied"() {
        given: "a tenant with a sandbox that answers when the question is prepared"
        def tenant = api.tenant()
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, sandboxToken: "t" * 40,
                sandboxUrls: "http://alpdesk-ai-sandbox-toolbox-${UUID.randomUUID().toString().substring(0, 8)}:8080".toString()])
        sandboxes.up = true
        def context = context(tenant, null, false)

        expect:
        names(context).contains("run_python")

        when: "the sandbox goes down during the answer"
        sandboxes.up = false

        then:
        call(context, "run_python", [code: "1 + 1"]).text() == "Error: the tool run_python is not available right now - go on without it"

        cleanup:
        sandboxes.reset()
    }

    def "a wrong call is answered for the model to correct: #reason"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)
        def uploaded = api.chatUpload(token, ["notiz.txt": "Text".getBytes()])
        def context = context(tenant, uploaded.conversation.id, true)
        def arguments = args.collectEntries { key, value -> [key, value == "FILE" ? uploaded.files[0].id : value] }

        expect:
        call(context, tool, arguments).text().startsWith("Error: " + error)

        where:
        reason                 | tool          | args                                  | error
        "an unknown tool"      | "send_mail"   | [:]                                   | "there is no tool send_mail"
        "a tool not offered"   | "run_python"  | [code: "1 + 1"]                       | "the tool run_python is not available right now"
        "no file id"           | "read_file"   | [mode: "TEXT"]                        | "the argument file_id must be a number"
        "a foreign file"       | "read_file"   | [file_id: 999999999, mode: "TEXT"]    | "there is no such file in this conversation"
        "an unknown mode"      | "read_file"   | [file_id: "FILE", mode: "ALL"]        | "mode must be TEXT, PAGES or ORIGINAL"
        "pages of a text file" | "read_file"   | [file_id: "FILE", mode: "PAGES"]      | "mode PAGES is for PDFs"
        "no content"           | "create_file" | [file_name: "a.txt", format: "TXT"]   | "the argument content is missing"
        "an unknown format"    | "create_file" | [file_name: "a", format: "EXE", content: "x"] | "format must be one of"
    }

    def "the chat shows our own tool by its name"() {
        expect:
        step(context(api.tenant(), null, false), "similarity_search", [query: "Garantie"]) ==
                new ToolStep("similarity_search", null)
    }

    def "the chat shows a connected software's call by its own names, not the one the model sees"() {
        given:
        def context = context(api.tenant(), null, false)
        def connection = new McpConnection(1L, 1L, "Lager Süd", "http://example.com/mcp", null, null, null,
                false, false, null, McpConnectionProtocol.MCP, false, Instant.now(), Instant.now())
        def tool = new McpTool(1L, 1L, "find_invoice", "Finds an invoice", "{}", false, true, true, true, null)
        def specification = ToolSpecification.builder().name("Lager_S_d__find_invoice").build()
        context.softwareTools([new McpTools.Offered(specification, connection, tool)])

        expect:
        step(context, "Lager_S_d__find_invoice", [customer: "Musterfirma"]) == new ToolStep("find_invoice", "Lager Süd")
    }

    def "read_file does not reach a file of another conversation"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)
        def mine = api.chatUpload(token, ["a.txt": "Meins".getBytes()])
        def other = api.chatUpload(token, ["b.txt": "Anderes".getBytes()])

        expect:
        call(context(tenant, mine.conversation.id, true), "read_file", [file_id: other.files[0].id, mode: "TEXT"]).text() ==
                "Error: there is no such file in this conversation"
    }

    def "search_file and read_file_lines come only with a large file sent with the question"() {
        given:
        def context = context(api.tenant(), null, false)

        expect:
        names(context) == ["create_file"]

        when:
        context.exploredFiles([ExploredFile.of("machine.log", "eine Zeile")])

        then:
        names(context) == ["create_file", "search_file", "read_file_lines"]
    }

    def "search_file finds lines case-insensitively, with alternatives, and says where to go on"() {
        given:
        def context = withLog(api.tenant(), (1..20).collect { it % 3 == 0 ? "line ${it} Error here" : it == 10 ? "line 10 FATAL" : "line ${it} ok" })

        when: "more matches than shown (5 in the specs)"
        def first = call(context, "search_file", [file_name: "MACHINE.log", query: "error|fatal"])
        def next = call(context, "search_file", [file_name: "machine.log", query: "error|fatal", from_line: 13])
        def none = call(context, "search_file", [file_name: "machine.log", query: "spindle"])

        then:
        first.text() == '7 matching lines in "machine.log", the first 5 shown - search more narrowly, or go on with ' +
                'from_line 13:\n3: line 3 Error here\n6: line 6 Error here\n9: line 9 Error here\n10: line 10 FATAL\n' +
                '12: line 12 Error here\n'
        next.text() == '2 matching lines in "machine.log":\n15: line 15 Error here\n18: line 18 Error here\n'
        none.text() == 'No line of "machine.log" matches.'
    }

    def "search_file stops at the first match beyond its text budget - from_line goes on there, none is skipped"() {
        given: "a long match that no longer fits the 2000 characters of the specs, then a short one"
        def wide = "Error " + "x" * 1200
        def context = withLog(api.tenant(), ["line 1 $wide", "line 2 $wide", "line 3 Error"])

        when:
        def first = call(context, "search_file", [file_name: "machine.log", query: "error"])

        then:
        first.text().startsWith('3 matching lines in "machine.log", the first 1 shown - search more narrowly, or go ' +
                'on with from_line 2:\n1: line 1 Error')
        !first.text().contains("3: line 3 Error")
    }

    def "read_file_lines reads a range - at most 10 lines per call in the specs"() {
        given:
        def context = withLog(api.tenant(), (1..30).collect { "line ${it}" })

        expect:
        call(context, "read_file_lines", [file_name: "machine.log", from_line: 4, to_line: 6]).text() ==
                'Lines 4 to 6 of 30 in "machine.log":\n4: line 4\n5: line 5\n6: line 6\n'
        call(context, "read_file_lines", [file_name: "machine.log", from_line: 28, to_line: 99]).text() ==
                'Lines 28 to 30 of 30 in "machine.log":\n28: line 28\n29: line 29\n30: line 30\n'
        call(context, "read_file_lines", [file_name: "machine.log", from_line: 1, to_line: 30]).text().startsWith(
                'Lines 1 to 10 of 30 in "machine.log" (at most 10 lines and 2000 characters per call - read on from line 11):\n1: line 1\n')
    }

    def "a long line is cut, and a call stops at the tool's text limit (2000 characters in the specs)"() {
        given:
        def context = withLog(api.tenant(), ["x" * 2500, "y" * 900, "z" * 900, "w" * 900])

        when:
        def first = call(context, "read_file_lines", [file_name: "machine.log", from_line: 1, to_line: 1])
        def rest = call(context, "read_file_lines", [file_name: "machine.log", from_line: 2, to_line: 4])

        then:
        first.text().contains("x" * 2000 + " [... 500 more characters]")
        rest.text().startsWith('Lines 2 to 3 of 4 in "machine.log" (at most 10 lines and 2000 characters per call - read on from line 4)')
    }

    def "a wrong call of the file tools is answered with an error for the model: #reason"() {
        given:
        def context = withLog(api.tenant(), (1..5).collect { "line ${it}" })

        expect:
        call(context, tool, arguments).text().startsWith("Error: " + error)

        where:
        reason             | tool              | arguments                                              || error
        "an unknown file"  | "search_file"     | [file_name: "other.log", query: "x"]                   || 'there is no file "other.log" - the files to read: machine.log'
        "no query"         | "search_file"     | [file_name: "machine.log"]                             || "the argument query is missing"
        "beyond the file"  | "read_file_lines" | [file_name: "machine.log", from_line: 6, to_line: 9]   || '"machine.log" has lines 1 to 5'
        "backwards"        | "read_file_lines" | [file_name: "machine.log", from_line: 3, to_line: 2]   || '"machine.log" has lines 1 to 5'
        "no number"        | "read_file_lines" | [file_name: "machine.log", from_line: "a", to_line: 2] || "the argument from_line must be a number"
    }

    private ToolContext withLog(Map tenant, List<String> lines) {
        def context = context(tenant, null, false)
        context.exploredFiles([ExploredFile.of("machine.log", lines.join("\n"))])
        context
    }

    private ToolContext context(Map tenantMap, Object conversationId, boolean hasFiles, Map reads = [readsPdf: true]) {
        def tenant = tenants.findById(tenantMap.id as Long).get()
        new ToolContext(tenant, ToolContext.Asker.CLIENT_SOFTWARE, models.findById(api.chatModel("GEMINI", reads).id as Long).get(), null,
                KnowledgeScope.everything(), databases.dataSource(tenant.databaseName()), conversationId as Long, hasFiles, 5,
                ToolContext.Files.KEPT)
    }

    private List<String> names(ToolContext context) {
        toolbox.specificationsFor(context)*.name()
    }

    private ToolResult call(ToolContext context, String name, Map arguments) {
        toolbox.execute(context, request(name, arguments))
    }

    private ToolStep step(ToolContext context, String name, Map arguments) {
        toolbox.step(context, request(name, arguments))
    }

    private static ToolExecutionRequest request(String name, Map arguments) {
        ToolExecutionRequest.builder().id("1").name(name)
                .arguments(io.micronaut.serde.ObjectMapper.getDefault().writeValueAsString(arguments)).build()
    }
}
