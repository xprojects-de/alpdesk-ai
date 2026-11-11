package de.alpdesk.services.mcp

import de.alpdesk.entities.mcp.McpConnection
import de.alpdesk.entities.mcp.McpConnectionProtocol
import de.alpdesk.entities.mcp.McpTool
import de.alpdesk.services.common.ServiceException
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.internal.JsonSchemaElementJsonUtils
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import io.micronaut.json.JsonMapper
import spock.lang.Specification

import java.time.Instant

/** How a connected software's tool reaches the model: a name every provider takes, and its parameters. */
class McpToolsSpec extends Specification {

    def "a tool's name takes only what every provider takes, at most 64 characters: #reason"() {
        expect:
        McpTools.uniqueName(connection, tool, taken as Set) == name
        name ==~ /[a-zA-Z0-9_-]{1,64}/

        where:
        reason               | connection      | tool                   | taken                                  || name
        "plain"              | "Buchhaltung"   | "find_invoice"         | []                                     || "Buchhaltung__find_invoice"
        "blanks and umlauts" | "Lager Süd"     | "Bestand prüfen"       | []                                     || "Lager_S_d__Bestand_pr_fen"
        "no name left"       | "Ä"             | "  "                   | []                                     || "___tool"
        "too long"           | "A" * 40        | "b" * 40               | []                                     || "A" * 40 + "__" + "b" * 22
        "taken"              | "Buchhaltung"   | "find_invoice"         | ["Buchhaltung__find_invoice"]          || "Buchhaltung__find_invoice_2"
        "too long and taken" | "A" * 40        | "b" * 40               | ["A" * 40 + "__" + "b" * 22]           || "A" * 40 + "__" + "b" * 20 + "_2"
    }

    def "a decided call's stored result is without the software's name the chat's results start with: #reason"() {
        given:
        def connection = new McpConnection(1L, 1L, "Buchhaltung", "http://example.com/mcp", null, null, null,
                false, null, McpConnectionProtocol.MCP, false, Instant.now(), Instant.now())
        def tool = new McpTool(1L, 1L, "book_invoice", "Books an invoice", "{}", true, true, true, true)
        def offered = new McpTools.Offered(ToolSpecification.builder().name("Buchhaltung__book_invoice").build(),
                connection, tool)

        expect:
        McpTools.withoutSoftwareName(result, offered) == stored

        where:
        reason                       | result                                   || stored
        "a result"                   | "[Buchhaltung] Gebucht: R-1001"          || "Gebucht: R-1001"
        "the software's error"       | "Error: [Buchhaltung] Periode gesperrt"  || "Error: Periode gesperrt"
        "an error of our own"        | "Error: the arguments are no JSON object" || "Error: the arguments are no JSON object"
    }

    def "a schema with keys langchain4j does not take on an object keeps its parameters: #reason"() {
        when:
        def parameters = JsonSchemaElementJsonUtils.fromMap(McpTools.objectSchema(schema))

        then:
        parameters instanceof JsonObjectSchema
        (parameters as JsonObjectSchema).properties().keySet() == ["number"] as Set
        (parameters as JsonObjectSchema).required() == ["number"]

        where:
        reason                          | schema
        "the Python SDK's title"        | [title: "find_invoiceArguments", type: "object", properties: [number: [type: "string", title: "Number"]], required: ["number"]]
        "the TypeScript SDK's \$schema" | ['$schema': "http://json-schema.org/draft-07/schema#", type: "object", properties: [number: [type: "string"]], required: ["number"]]
        "a schema as additionalProperties" | [type: "object", properties: [number: [type: "string"]], required: ["number"], additionalProperties: [type: "string"]]
    }

    def "arguments that are no JSON object are a text for the model, not a failed answer: #arguments"() {
        when:
        McpTools.arguments(JsonMapper.createDefault(), arguments)

        then:
        def error = thrown(ServiceException)
        error.message == "the arguments are no JSON object"

        where:
        arguments << ["[1]", '"x"', "42", "{"]
    }
}
