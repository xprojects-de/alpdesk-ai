package de.alpdesk.services.mcp

import de.alpdesk.services.common.InternalAddresses
import de.alpdesk.services.ai.ModelServers
import de.alpdesk.services.common.ServiceException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * The MCP client against a fake software: a session per use, the key as bearer, answers as JSON or as an event stream,
 * the tools' readOnlyHint - and no redirect followed, no internal address called, failures naming the connection.
 */
@MicronautTest(startApplication = false)
class McpClientSpec extends Specification {

    /** A tenant without a look of its own: the client introduces itself with the installation's name. */
    static final Long NO_TENANT = 999_999_999L

    FakeMcpSoftware software = new FakeMcpSoftware()

    @Inject
    McpClient client

    @Inject
    LinkDownloads downloads

    def cleanup() {
        software.stop()
        downloads.maxTime = LinkDownloads.MAX_TIME
    }

    def "lists the tools with whether they only read - as JSON and as an event stream"() {
        given:
        software.eventStream = eventStream

        when:
        def tools = client.listTools(NO_TENANT, "Buchhaltung", software.url(), null)

        then:
        tools*.name == ["find_invoice", "cancel_invoice"]
        tools*.readOnly == [true, false]
        tools[0].inputSchema().required == ["number"]

        and: "a session: initialize, then the notification and the request with its id"
        software.received*.method == ["initialize", "notifications/initialized", "tools/list"]
        software.received*.session == [null, "session-1", "session-1"]

        where:
        eventStream << [false, true]
    }

    def "reads every page of tools - and gives up on a server that keeps sending pages: #reason"() {
        given:
        software.nextCursor = nextCursor

        when:
        def tools = client.listTools(NO_TENANT, "Buchhaltung", software.url(), null)

        then:
        tools.size() == 6

        when:
        software.nextCursor = endless
        client.listTools(NO_TENANT, "Buchhaltung", software.url(), null)

        then:
        def e = thrown(ServiceException)
        e.message == "Buchhaltung keeps sending more pages of tools"
        software.received.count { it.method == "tools/list" } <= 3 + McpClient.MAX_TOOL_PAGES

        where:
        reason                 | endless
        "the same page again"  | { String cursor -> "page-2" }
        "ever more pages"      | { String cursor -> UUID.randomUUID().toString() }
        nextCursor = { String cursor -> cursor == null ? "page-2" : cursor == "page-2" ? "page-3" : null }
    }

    def "gives up on a server offering more than the limits allow: #reason"() {
        given:
        software.tools = tools

        when:
        client.listTools(NO_TENANT, "Buchhaltung", software.url(), null)

        then:
        def e = thrown(ServiceException)
        e.message == message

        where:
        reason                | tools                                                       || message
        "too many tools"      | (1..McpClient.MAX_TOOLS + 1).collect { tool("tool_$it") }   || "Buchhaltung offers more than 500 tools"
        "a too long name"     | [tool("t" * (McpClient.MAX_TOOL_NAME + 1))]                 || "Buchhaltung offers a tool with a name longer than 128 characters"
        "a too large schema"  | [tool("find", [type: "string", description: "x" * McpClient.MAX_SCHEMA])] || "Buchhaltung offers a tool with an input schema longer than 20000 characters"
        "a too large message" | (1..3).collect { tool("tool_$it", null, "y" * 3_000_000) } || "Buchhaltung sent an answer larger than 8 MB"
        "a name twice"        | [tool("find"), tool("list"), tool("find")]                 || "Buchhaltung offers the tool find twice"
    }

    def "a long tool description is cut, the tools up to the limit are read"() {
        given:
        software.tools = (1..McpClient.MAX_TOOLS).collect { tool("tool_$it", null, it == 1 ? "d" * 10_000 : "short") }

        when:
        def tools = client.listTools(NO_TENANT, "Buchhaltung", software.url(), null)

        then:
        tools.size() == McpClient.MAX_TOOLS
        tools[0].description() == "d" * McpClient.MAX_DESCRIPTION + " [cut off]"
        tools[1].description() == "short"
    }

    def "a long result is cut for the model, a long error of the software too"() {
        when:
        software.callText = "r" * (McpClient.MAX_RESULT_TEXT + 10)
        def result = client.callTool(NO_TENANT, "Buchhaltung", software.url(), null, "find_invoice", [number: "4711"])

        then:
        result.text() == "r" * McpClient.MAX_RESULT_TEXT + "\n[cut off - the result was longer]"

        when:
        software.callError = "e" * 100_000
        client.callTool(NO_TENANT, "Buchhaltung", software.url(), null, "find_invoice", [number: "4711"])

        then:
        def e = thrown(ServiceException)
        e.message == "Buchhaltung answered with an error: " + "e" * McpClient.MAX_ERROR_TEXT + " [cut off]"
    }

    def "calls a tool with the key as bearer; its error is reported, not thrown"() {
        given:
        software.key = "software-key-123"

        when:
        def found = client.callTool(NO_TENANT, "Buchhaltung", software.url(), Credential.key("software-key-123"), "find_invoice", [number: "4711"])
        def missing = client.callTool(NO_TENANT, "Buchhaltung", software.url(), Credential.key("software-key-123"), "find_invoice", [number: "0000"])

        then:
        found.text() == "Invoice 4711: 412,50 EUR, paid"
        !found.error()
        missing.error()
        software.calls()*.params*.arguments == [[number: "4711"], [number: "0000"]]
        software.received*.authorization.every { it == "Bearer software-key-123" }
    }

    def "files the tool returns as embedded resources or images come decoded, apart from the text; audio is left out"() {
        given:
        def zip = FakeMcpSoftware.zip()
        software.callContent = [
                [type: "text", text: "Test program generated"],
                FakeMcpSoftware.blob("file:///demo-project.zip", "application/zip", zip),
                [type: "resource", resource: [uri: "file:///notes/readme.md", mimeType: "text/markdown", text: "# Readme"]],
                [type: "resource", resource: [uri: "file:///broken.zip", mimeType: "application/zip", blob: "no base64!"]],
                [type: "image", data: "iVBORw0KGgo=", mimeType: "image/png"],
                [type: "image", data: "no base64!", mimeType: "image/png"],
                [type: "audio", data: "AAAA", mimeType: "audio/wav"]]

        when:
        def result = client.callTool(NO_TENANT, "Buchhaltung", software.url(), null, "find_invoice", [number: "4711"])

        then:
        result.text() == 'Test program generated\n[file "broken.zip" left out - its content is no valid base64]\n' +
                '[image left out - its content is no valid base64]\n[audio content left out]'
        result.files()*.name() == ["demo-project.zip", "readme.md", "bild"]
        result.files()*.mimeType() == ["application/zip", "text/markdown", "image/png"]
        result.files()[2].content() == "iVBORw0KGgo=".decodeBase64()
        result.files()[0].content() == zip
        new String(result.files()[1].content(), "UTF-8") == "# Readme"
    }

    def "files the tool returns as links are fetched at once - a uri of the server's own with resources/read, a web address downloaded"() {
        given:
        def zip = FakeMcpSoftware.zip()
        software.resources["dms://archive/4711"] = FakeMcpSoftware.blob("dms://archive/4711", "application/pdf", "%PDF-1.4".bytes).resource
        software.downloads["beleg-7.csv"] = [body: "date;amount\n2026-03-07;12.50".bytes, type: "text/csv; charset=utf-8"]
        software.callContent = [
                [type: "text", text: "Two documents"],
                FakeMcpSoftware.link("dms://archive/4711", "Rechnung 4711.pdf"),
                FakeMcpSoftware.blob("file:///demo-project.zip", "application/zip", zip),
                FakeMcpSoftware.link(software.fileUrl("beleg-7.csv"), "")]

        when:
        def result = client.callTool(NO_TENANT, "Buchhaltung", software.url(), null, "find_invoice", [number: "4711"])

        then: "in the order of the answer, named by the link (else its uri), typed by the server"
        result.text() == "Two documents"
        result.files()*.name() == ["Rechnung 4711.pdf", "demo-project.zip", "beleg-7.csv"]
        result.files()*.mimeType() == ["application/pdf", "application/zip", "text/csv"]
        new String(result.files()[0].content(), "UTF-8") == "%PDF-1.4"
        new String(result.files()[2].content(), "UTF-8").startsWith("date;amount")

        and: "the uri of the server's own read in the session of the call"
        def read = software.received.find { it.method == "resources/read" }
        read.params.uri == "dms://archive/4711"
        read.session == software.received.find { it.method == "tools/call" }.session
    }

    def "a link's download gets our bearer only on the connection's own origin - never a download host elsewhere"() {
        given:
        def storage = new FakeMcpSoftware()
        software.key = "software-key-123"
        software.downloads["own.pdf"] = [body: "%PDF own".bytes, type: "application/pdf"]
        storage.downloads["signed.pdf"] = [body: "%PDF signed".bytes, type: "application/pdf"]
        software.downloads["moved.pdf"] = [status: 302, location: storage.fileUrl("signed.pdf")]
        software.callContent = [
                FakeMcpSoftware.link(software.fileUrl("own.pdf"), "own.pdf"),
                FakeMcpSoftware.link(storage.fileUrl("signed.pdf"), "signed.pdf"),
                FakeMcpSoftware.link(software.fileUrl("moved.pdf"), "moved.pdf")]

        when:
        def result = client.callTool(NO_TENANT, "Buchhaltung", software.url(), Credential.key("software-key-123"),
                "find_invoice", [number: "4711"])

        then:
        result.files()*.name() == ["own.pdf", "signed.pdf", "moved.pdf"]
        new String(result.files()[2].content(), "UTF-8") == "%PDF signed"
        software.downloadRequests*.authorization == ["Bearer software-key-123", "Bearer software-key-123"]
        storage.downloadRequests*.authorization == [null, null]

        cleanup:
        storage.stop()
    }

    def "a link that gives no file is a note for the model, never a failed call: #reason"() {
        given:
        software.downloads["gone.pdf"] = [status: 410]
        software.downloads["error.pdf"] = [status: 500]
        software.downloads["to-internal.pdf"] = [status: 302, location: "https://10.0.0.1/beleg.pdf"]
        software.downloads["loop.pdf"] = [status: 302, location: software.fileUrl("loop.pdf")]
        software.downloads["to-nowhere.pdf"] = [status: 302, location: "https://dms.example.com/Beleg 2026.pdf"]
        software.downloads["to-ftp.pdf"] = [status: 302, location: "ftp://127.0.0.1/beleg.pdf"]
        software.resources["dms://archive/other"] = FakeMcpSoftware.blob("dms://archive/other", "application/pdf", "x".bytes).resource
        software.callContent = [[type: "text", text: "The document"], FakeMcpSoftware.link(uri(software), "beleg.pdf")]

        when:
        def result = client.callTool(NO_TENANT, "Buchhaltung", software.url(), null, "find_invoice", [number: "4711"])

        then:
        !result.error()
        result.files().isEmpty()
        result.text() == "The document\n[file \"beleg.pdf\" could not be fetched: " + note + "]"
        McpClient.unfetchedNotes(result) == "[file \"beleg.pdf\" could not be fetched: " + note + "]"

        where:
        reason                          | uri                                          | note
        "expired"                       | { FakeMcpSoftware s -> s.fileUrl("gone.pdf") }      | "the link has expired or the file is gone (HTTP 410)"
        "a server error"                | { FakeMcpSoftware s -> s.fileUrl("error.pdf") }     | "the server answered HTTP 500"
        "an internal address"           | { FakeMcpSoftware s -> "https://10.0.0.1/beleg.pdf" } | "its address is not allowed - Buchhaltung: The address leads into an internal network - only the hosts in APP_AI_PLAIN_HTTP_HOSTS may"
        "a redirect into the network"   | { FakeMcpSoftware s -> s.fileUrl("to-internal.pdf") } | "its address is not allowed - Buchhaltung: The address leads into an internal network - only the hosts in APP_AI_PLAIN_HTTP_HOSTS may"
        "endless redirects"             | { FakeMcpSoftware s -> s.fileUrl("loop.pdf") }      | "more than 3 redirects"
        "plain http to a public host"   | { FakeMcpSoftware s -> "http://example.com/beleg.pdf" } | "its address is not allowed - The server of Buchhaltung may not be reached over plain HTTP - ask the admin"
        "a uri the server cannot read"  | { FakeMcpSoftware s -> "dms://archive/unknown" }      | "the server refused to read it - Resource not found"
        "a space in the link"           | { FakeMcpSoftware s -> s.fileUrl("Beleg 2026.pdf?ids[]=5") } | "the link is no address to fetch"
        "a redirect to no address"      | { FakeMcpSoftware s -> s.fileUrl("to-nowhere.pdf") } | "it was sent on to no valid address"
        "a redirect to another scheme"  | { FakeMcpSoftware s -> s.fileUrl("to-ftp.pdf") }    | "the link is no address to fetch"
        "a link without an address"     | { FakeMcpSoftware s -> null }                       | "the link has no address"
    }

    def "a linked file's server too slow #when is a note - the limit counts the whole download, the body too"() {
        given: "the limit shortened from a minute, so the spec does not wait one"
        downloads.maxTime = java.time.Duration.ofMillis(500)
        software.downloads["slow.pdf"] = [body: "%PDF".bytes, type: "application/pdf"] + delay
        software.callContent = [FakeMcpSoftware.link(software.fileUrl("slow.pdf"), "slow.pdf")]

        when:
        def result = client.callTool(NO_TENANT, "Buchhaltung", software.url(), null, "find_invoice", [number: "4711"])

        then:
        !result.error()
        result.files().isEmpty()
        result.text() == '[file "slow.pdf" could not be fetched: it took longer than 60 seconds]'

        where:
        when                      | delay
        "before its headers"      | [headerDelayMillis: 2_000]
        "between headers and body" | [bodyDelayMillis: 2_000]
    }

    def "a uri of the server's own from a server that offers no resources is a note - the SDK refuses before asking it"() {
        given: "a server without the resources capability linking to a uri of its own"
        software.callContent = [[type: "text", text: "The document"], FakeMcpSoftware.link("dms://archive/beleg", "beleg.pdf")]

        when:
        def result = client.callTool(NO_TENANT, "Buchhaltung", software.url(), null, "find_invoice", [number: "4711"])

        then:
        !result.error()
        result.files().isEmpty()
        result.text().startsWith('The document\n[file "beleg.pdf" could not be fetched: the server refused to read it - ')
        !software.received*.method.contains("resources/read")
    }

    def "a link's name without one of its own leaves a signed link's query out"() {
        expect:
        McpClient.fileName("https://dms.example.com/files/Beleg 2026.pdf?sig=SECRET&exp=1") == "Beleg 2026.pdf"
        McpClient.fileName("https://dms.example.com/files/beleg.pdf?sig=SECRET") == "beleg.pdf"
    }

    def "a linked file larger than a chat file's limit is not taken - its download stops at the limit"() {
        given:
        software.downloads["huge.zip"] = [body: new byte[LinkDownloads.MAX_BYTES + 1], type: "application/zip"]
        software.callContent = [FakeMcpSoftware.link(software.fileUrl("huge.zip"), "huge.zip")]

        when:
        def result = client.callTool(NO_TENANT, "Buchhaltung", software.url(), null, "find_invoice", [number: "4711"])

        then:
        result.files().isEmpty()
        result.text() == "[file \"huge.zip\" could not be fetched: the file is larger than 50 MB]"
    }

    def "links beyond an answer's files are not fetched, nor those of a failed call"() {
        given:
        (1..6).each { software.downloads["beleg-${it}.pdf".toString()] = [body: "%PDF ${it}".bytes, type: "application/pdf"] }
        software.callContent = [FakeMcpSoftware.blob("file:///first.zip", "application/zip", FakeMcpSoftware.zip())] +
                (1..6).collect { FakeMcpSoftware.link(software.fileUrl("beleg-${it}.pdf"), "beleg-${it}.pdf") }

        when:
        def result = client.callTool(NO_TENANT, "Buchhaltung", software.url(), null, "find_invoice", [number: "4711"])

        then:
        result.files()*.name() == ["first.zip", "beleg-1.pdf", "beleg-2.pdf", "beleg-3.pdf", "beleg-4.pdf"]
        result.text() == '[file "beleg-5.pdf" not fetched - an answer takes at most 5 files]\n' +
                '[file "beleg-6.pdf" not fetched - an answer takes at most 5 files]'
        software.downloadRequests*.path == ["beleg-1.pdf", "beleg-2.pdf", "beleg-3.pdf", "beleg-4.pdf"]

        when:
        software.downloadRequests.clear()
        software.callContent = [[type: "text", text: "Failed"], FakeMcpSoftware.link(software.fileUrl("beleg-1.pdf"), "beleg-1.pdf")]
        software.callIsError = true
        def failed = client.callTool(NO_TENANT, "Buchhaltung", software.url(), null, "find_invoice", [number: "4711"])

        then:
        failed.error()
        failed.files().isEmpty()
        failed.text() == 'Failed\n[file "beleg-1.pdf" not fetched - the call failed]'
        software.downloadRequests.isEmpty()
    }

    def "a connection's reading gets tools and prompts in one session - none from a server without prompts"() {
        when:
        def offer = client.read(NO_TENANT, "Buchhaltung", software.url(), null)

        then:
        offer.tools()*.name() == ["find_invoice", "cancel_invoice"]
        offer.prompts()*.name() == ["create_test_program"]
        offer.prompts()[0].arguments()*.required() == [true, true, false]
        offer.promptsError() == null
        software.received*.method == ["initialize", "notifications/initialized", "tools/list", "prompts/list"]

        when:
        software.received.clear()
        software.prompts = null
        offer = client.read(NO_TENANT, "Buchhaltung", software.url(), null)

        then: "not asked for prompts it does not offer"
        offer.prompts() == []
        software.received*.method == ["initialize", "notifications/initialized", "tools/list"]
    }

    def "prompts beyond the limits are no failure of the reading - the tools are read, the prompts named: #reason"() {
        given:
        setup(software)

        when:
        def offer = client.read(NO_TENANT, "Buchhaltung", software.url(), null)

        then:
        offer.tools().size() == 2
        offer.prompts() == null
        offer.promptsError() == message

        where:
        reason               | setup                                                                                                  || message
        "too many arguments" | { FakeMcpSoftware s -> s.prompts = [[name: "p", arguments: (1..21).collect { [name: "a$it".toString()] }]] }      || "Buchhaltung offers a prompt with more than 20 arguments or too long a name of one"
        "a too long name"    | { FakeMcpSoftware s -> s.prompts = [[name: "p" * (McpClient.MAX_TOOL_NAME + 1)]] }                    || "Buchhaltung offers a prompt with a name longer than 128 characters"
        "endless pages"      | { FakeMcpSoftware s -> s.nextPromptCursor = { String cursor -> "page-2" } }                            || "Buchhaltung keeps sending more pages of prompts"
        "a name twice"       | { FakeMcpSoftware s -> s.prompts = [[name: "p"], [name: "p"]] }                                        || "Buchhaltung offers the prompt p twice"
    }

    def "a prompt's text is the text of its user messages - no assistant's, no image; without text it is a 502"() {
        when:
        def text = client.getPrompt(NO_TENANT, "Buchhaltung", software.url(), null, "create_test_program",
                [package_name: "com.example.demo", project_name: "demo"])

        then:
        text == "Erstelle ein Prüfprogramm für com.example.demo im Projekt demo.\n\nAnmerkung: keine"

        when:
        software.promptError = "boom"
        client.getPrompt(NO_TENANT, "Buchhaltung", software.url(), null, "create_test_program", [:])

        then:
        def e = thrown(ServiceException)
        e.message == "Buchhaltung answered with an error: boom"

        when:
        software.promptError = null
        software.promptMessages = [[role: "assistant", content: [type: "text", text: "Gern."]]]
        client.getPrompt(NO_TENANT, "Buchhaltung", software.url(), null, "create_test_program", [:])

        then:
        def none = thrown(ServiceException)
        none.message == "Buchhaltung answered the prompt without a text"
    }

    def "a server refusing a prompt's values with HTTP #status (stateless: #stateless) is not taken for a non-MCP server"() {
        given:
        software.promptError = "project_name must be lowercase"
        software.errorStatus = status
        software.stateless = stateless

        when:
        client.getPrompt(NO_TENANT, "Buchhaltung", software.url(), null, "create_test_program",
                [package_name: "com.example.demo", project_name: "AiApp"])

        then:
        def e = thrown(ServiceException)
        e.message == message

        where:
        status | stateless || message
        200    | false     || "Buchhaltung answered with an error: project_name must be lowercase"
        400    | true      || "Buchhaltung answered with an error: project_name must be lowercase"
        500    | false     || "Buchhaltung answered with an error: project_name must be lowercase"
        500    | true      || "Buchhaltung answered with an error: project_name must be lowercase"
        // The SDK ends a session on a 400 and drops the server's answer - its reason is lost.
        400    | false     || "Buchhaltung could not carry out the request - e.g. a value it does not accept"
    }

    def "a returned file is named by the last part of its uri: #uri"() {
        expect:
        McpClient.fileName(uri) == name

        where:
        uri                              || name
        "file:///demo-project.zip"       || "demo-project.zip"
        "file:///out/Pr%C3%BCfung.zip"   || "Prüfung.zip"
        "https://example.com/files/a.pdf" || "a.pdf"
        "file:///"                       || ""
        "no uri at all"                  || "no uri at all"
        null                             || ""
    }

    def "failures name the connection, never the address: #reason"() {
        given:
        setup(software)

        when:
        client.listTools(NO_TENANT, "Buchhaltung", url ?: software.url(), Credential.key(key))

        then:
        def e = thrown(ServiceException)
        e.message == message
        !e.params().containsKey("host")

        where:
        reason               | setup                                           | url                         | key     || message
        "a refused key"      | { FakeMcpSoftware s -> s.key = "right-key-1" }  | null                        | "wrong" || "Buchhaltung refused our key - check it in the connection"
        "a redirect"         | { FakeMcpSoftware s -> s.redirectTo = "http://127.0.0.1:1/mcp" } | null       | null    || "Buchhaltung did not answer as an MCP server"
        "nothing listening"  | { FakeMcpSoftware s -> }                        | "http://127.0.0.1:1/mcp"    | null    || "Buchhaltung is not reachable"
        "an internal host"   | { FakeMcpSoftware s -> }                        | "https://10.1.2.3/mcp"      | null    || "Buchhaltung: The address leads into an internal network - only the hosts in APP_AI_PLAIN_HTTP_HOSTS may"
        "an unknown host"    | { FakeMcpSoftware s -> }                        | "https://erp.invalid/mcp"   | null    || "Buchhaltung: The host of its address is not known"
    }

    def "an unknown host is named when the address is saved - to the admin who typed it"() {
        when:
        new McpAddresses(new ModelServers("")).normalize("https://erp.invalid/mcp")

        then:
        def e = thrown(ServiceException)
        e.code() == "mcpAddress.unknownHost"
        e.message == "The host erp.invalid is not known"
        e.params() == [host: "erp.invalid"]
    }

    def "internal addresses are refused when an address is saved: #address"() {
        given:
        def addresses = new McpAddresses(new ModelServers(""))

        when:
        addresses.normalize(address)

        then:
        thrown(ServiceException)

        where:
        address << ["https://127.0.0.2/mcp", "https://10.0.0.5/mcp", "https://192.168.1.10/mcp", "https://169.254.169.254/mcp",
                    "https://[fd00::1]/mcp", "https://100.64.1.1/mcp", "http://software.example.com/mcp"]
    }

    private static Map tool(String name, Map property = null, String description = "A tool") {
        [name: name, description: description,
         inputSchema: [type: "object", properties: property == null ? [:] : [value: property]],
         annotations: [readOnlyHint: true]]
    }

    def "a public address is not internal"() {
        expect:
        !InternalAddresses.isInternal(InetAddress.getByName("8.8.8.8"))
        InternalAddresses.isInternal(InetAddress.getByName("172.16.0.1"))
    }
}
