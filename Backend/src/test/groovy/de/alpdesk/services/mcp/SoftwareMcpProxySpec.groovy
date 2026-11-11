package de.alpdesk.services.mcp

import spock.lang.Specification

/**
 * The names of passed-through tools and prompts: `<connection>__<tool>` as they are where that fits, else cut and ending
 * in the tool's id - each name from its own tool alone, so it never moves to another tool.
 */
class SoftwareMcpProxySpec extends Specification {

    def "#connection / #tool is named #name"() {
        expect:
        SoftwareMcpProxy.name(connection, tool, id) == name
        name.length() <= SoftwareMcpProxy.MAX_NAME
        SoftwareMcpProxy.isPassedThrough(name)

        where:
        connection        | tool                   | id || name
        "Buchhaltung"     | "find_invoice"         | 7  || "Buchhaltung__find_invoice"
        "ERP-2"           | "get-order"            | 3  || "ERP-2__get-order"
        "Lager_Nord"      | "find"                 | 4  || "Lager_Nord__find"
        "Lager Nord"      | "find"                 | 3  || "Lager_Nord__find__3"
        "A_"              | "b"                    | 5  || "A___b__5"
        "A"               | "_b"                   | 6  || "A___b__6"
        "Warenwirtschaft" | "get_customer_by_id"   | 17 || "Warenwirtscha__get_customer___17"
        "Warenwirtschaft" | "get_customer_by_name" | 18 || "Warenwirtscha__get_customer___18"
    }

    def "a long software name is cut before a long tool name - the tool keeps at least half"() {
        expect:
        SoftwareMcpProxy.name("Sehr lange Buchhaltungssoftware der Musterfirma", "find_invoice_by_customer_number", 1234) ==
                "Sehr_lange_B__find_invoice__1234"
        SoftwareMcpProxy.name("ERP", "find_invoice_by_customer_number_and_date", 9) == "ERP__find_invoice_by_customer__9"
    }
}
