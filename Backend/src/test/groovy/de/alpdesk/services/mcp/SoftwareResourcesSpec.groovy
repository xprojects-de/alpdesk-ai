package de.alpdesk.services.mcp

import de.alpdesk.entities.mcp.McpConnection
import de.alpdesk.entities.mcp.McpConnectionProtocol
import spock.lang.Specification

import java.time.Instant

/**
 * A connection's part of a resource's address (`ticketsystem.ticket://...`): lower case, letters, digits and "-",
 * starting with a letter, no dot - unique among the tenant's connections, so it finds its connection again. And the
 * protocol's own _meta keys kept back.
 */
class SoftwareResourcesSpec extends Specification {

    def "#name is the part #part"() {
        expect:
        SoftwareResources.parts([connection(17, name)]) == [17L: part]

        where:
        name               || part
        "Ticketsystem"     || "ticketsystem"
        "Lager Süd"        || "lager-sud"
        "ERP-2"            || "erp-2"
        "  Build.Server  " || "build-server"
        "4Gewinnt"         || "c17"
        "!!!"              || "c17"
        "Größe_Maß"        || "gro-e-ma"
    }

    def "parts that come out alike all get their connection's id - and never meet another part"() {
        expect:
        SoftwareResources.parts([connection(3, "Ticket System"), connection(5, "ticket-system"), connection(8, "Buchhaltung")]) ==
                [3L: "ticket-system-3", 5L: "ticket-system-5", 8L: "buchhaltung"]
        SoftwareResources.parts([connection(5, "Lager 5"), connection(9, "Lager-5")]) == [5L: "lager-5", 9L: "lager-5-9"]
        SoftwareResources.parts([connection(17, "C21"), connection(21, "17 Grad"), connection(30, "c21-21")]) ==
                [17L: "c21-17", 21L: "c21-21", 30L: "c21-21-30"]
    }

    def "an A2A agent has no part - it has no resources to pass"() {
        expect:
        SoftwareResources.parts([connection(4, "Agent", McpConnectionProtocol.A2A)]) == [:]
    }

    def "a web address is never prefixed, any other is"() {
        expect:
        SoftwareResources.prefixed("tickets", "ticket://4711/log") == "tickets.ticket://4711/log"
        SoftwareResources.prefixed("tickets", "https://files.example.com/a.pdf") == "https://files.example.com/a.pdf"
        SoftwareResources.prefixed("tickets", "HTTP://files.example.com/a.pdf") == "HTTP://files.example.com/a.pdf"
        SoftwareResources.prefixed("tickets", "file:///a.txt") == "tickets.file:///a.txt"
    }

    def "the _meta keys MCP reserves for itself stay back, every other goes on"() {
        expect:
        SoftwareMcpProxy.passedMeta(["io.modelcontextprotocol/related-task": 1, "dev.mcp/x": 2, "mcp/x": 3,
                                     "anthropic/requiresUserInteraction": true, "com.example.mcp/y": 4, "plain": 5]) ==
                ["mcp/x": 3, "anthropic/requiresUserInteraction": true, "com.example.mcp/y": 4, "plain": 5]
        SoftwareMcpProxy.passedMeta(null) == null
    }

    private static McpConnection connection(long id, String name, McpConnectionProtocol protocol = McpConnectionProtocol.MCP) {
        new McpConnection(id, 1L, name, "http://example.com/mcp", null, null, null, true, true, null, protocol, false,
                Instant.now(), Instant.now())
    }
}
