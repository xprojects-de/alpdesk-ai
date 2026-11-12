package de.alpdesk.services.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.alpdesk.services.oauth.FakeAuthorizationServer
import io.micronaut.serde.ObjectMapper

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tenant's software with an MCP server, for the specs: streamable HTTP with a session id, answering as JSON or - with
 * {@code eventStream} - as an event stream, checking a bearer key if it has one. Its tools: {@code find_invoice}
 * (readOnlyHint; its schema as the Python SDK writes it, with "title" and "$schema") and {@code cancel_invoice} (no
 * hint - it changes something); {@code tools} can be replaced, and {@code callText} / {@code callContent} /
 * {@code callError} replace what every tool call answers. Its resources: {@code resources} (read), {@code resourcePages}
 * and {@code templatePages} (listed). Its prompt {@code create_test_program} (arguments
 * package_name and project_name, both required) answers with a user's text naming them - plus an assistant's message
 * and an image, which a client leaves out; {@code prompts} null: a server without prompts.
 * <p>
 * With {@code oauth} it signs in with OAuth like a hosted server: a bearer of the fake authorization server's
 * {@code issuedTokens} is valid, anything else gets 401 with {@code WWW-Authenticate} naming its resource metadata
 * (RFC 9728), which it serves - its shape set by the fields below.
 */
class FakeMcpSoftware {

    final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    /** Every JSON-RPC message received: [method, params, authorization, session]. */
    final List<Map> received = new CopyOnWriteArrayList<>()
    volatile String key = null
    /** The authorization server it sends clients to; null: it signs in with no OAuth. */
    volatile FakeAuthorizationServer oauth = null
    /** Its 401 carries a challenge without resource metadata, and it serves none: a server that wants a fixed key. */
    volatile boolean headerWithoutMetadata = false
    /** Its challenge names the resource metadata (else a client looks at the well-known addresses). */
    volatile boolean headerNamesMetadata = true
    /** The resource metadata at the root, not at the address with the path appended. */
    volatile boolean metadataAtRoot = false
    /** The scope its challenge names; null: none. */
    volatile String headerScope = "read write"
    /** The scopes its resource metadata lists; empty: none. */
    volatile List<String> metadataScopes = ["read", "write"]
    /** The resource metadata names this host instead of its own (a challenge pointing elsewhere). */
    volatile String metadataUrlOverride = null
    /** Every token is refused, also one just renewed - the client was revoked at the server. */
    volatile boolean refuseEveryToken = false
    /** A tool call is answered 403 insufficient_scope - the server wants more rights (step-up). */
    volatile boolean insufficientScope = false
    /** The paths of the well-known addresses asked. */
    final List<String> metadataRequests = new CopyOnWriteArrayList<>()
    volatile boolean eventStream = false
    volatile String redirectTo = null
    /** The next page's cursor tools/list sends, from the cursor asked with (null on the first page); null: no more. */
    volatile Closure<String> nextCursor = { String cursor -> null }
    /** The text every tool call answers with instead of its own; null: the tool's own answer. */
    volatile String callText = null
    /** The content every tool call answers with instead of its own - text, embedded resources ({@link #blob}); null: its own. */
    volatile List<Map> callContent = null
    /** A tool's own answer by its name: the arguments in, the result (content, isError) out. */
    final Map<String, Closure<Map>> answers = new java.util.concurrent.ConcurrentHashMap<>()
    /** A call whose argument "source" begins with one of these is answered as an error of the tool. */
    final Set<String> refusedSources = java.util.concurrent.ConcurrentHashMap.<String> newKeySet()
    /** Whether {@code callContent} is answered as an error of the tool (isError). */
    volatile boolean callIsError = false
    /** The structured content and the _meta every answer of {@code callContent} carries; null: none. */
    volatile Map callStructured = null
    volatile Map callMeta = null
    /** The prompts the server lists; null: it offers none (no prompts capability). */
    volatile List<Map> prompts = [
            [name: "create_test_program", title: "Prüfprogramm erstellen", description: "Creates a test program",
             arguments: [[name: "package_name", description: "The Java package", required: true],
                         [name: "project_name", description: "The project name", required: true],
                         [name: "comment", description: "Optional remark"]]]]
    /** The next page's cursor prompts/list sends, from the cursor asked with; null: no more. */
    volatile Closure<String> nextPromptCursor = { String cursor -> null }
    /** The messages every prompts/get answers with instead of its own; null: its own. */
    volatile List<Map> promptMessages = null
    /** The JSON-RPC error message every prompts/get answers with; null: no error. */
    volatile String promptError = null
    /** The JSON-RPC error message every tool call answers with; null: no error. */
    volatile String callError = null
    /** The HTTP status a JSON-RPC error is sent with - servers on the MCP Java SDK send 400 or 500, not 200. */
    volatile int errorStatus = 200
    /** How long it waits before it answers a tool call - a software that takes its time, or hangs. */
    volatile long callDelayMillis = 0
    /** The instructions its answer to initialize gives; null: none. */
    volatile String instructions = null
    /** Whether the server is stateless: it sends no session id with its answer to initialize. */
    volatile boolean stateless = false
    /**
     * What resources/read answers by uri: a resource's contents ({@link #blob}'s resource). Empty: the server offers
     * no resources (no resources capability).
     */
    final Map<String, Map> resources = new java.util.concurrent.ConcurrentHashMap<>()
    /**
     * What resources/list and resources/templates/list answer, page by page: the cursor asked with ("" on the first
     * page) to the page ([resources: ..., nextCursor: ...] or [resourceTemplates: ...]). Empty: a page of none.
     */
    final Map<String, Map> resourcePages = new java.util.concurrent.ConcurrentHashMap<>()
    final Map<String, Map> templatePages = new java.util.concurrent.ConcurrentHashMap<>()
    /**
     * Its downloads under /files/ by the path after it: [status, body (bytes), type, location, headerDelayMillis,
     * bodyDelayMillis - a slow server before its headers or between them and the body]. A file a tool links to with an
     * http address ({@link #link}).
     */
    final Map<String, Map> downloads = new java.util.concurrent.ConcurrentHashMap<>()
    /** Every download asked for: [path, authorization]. */
    final List<Map> downloadRequests = new CopyOnWriteArrayList<>()
    volatile List<Map> tools = [
            [name: "find_invoice", description: "Finds an invoice by its number",
             inputSchema: ['$schema': "https://json-schema.org/draft/2020-12/schema", title: "find_invoiceArguments",
                           type: "object",
                           properties: [number: [type: "string", title: "Number", description: "The invoice number"]],
                           required: ["number"]],
             annotations: [readOnlyHint: true]],
            [name: "cancel_invoice", description: "Cancels an invoice",
             inputSchema: [type: "object", properties: [number: [type: "string"]], required: ["number"]]]]

    FakeMcpSoftware() {
        http.createContext("/mcp") { HttpExchange exchange -> handle(exchange) }
        http.createContext("/.well-known/") { HttpExchange exchange -> resourceMetadata(exchange) }
        http.createContext("/files/") { HttpExchange exchange -> download(exchange) }
        http.start()
    }

    String url() {
        "http://127.0.0.1:${http.address.port}/mcp"
    }

    /** The address of one of its downloads. */
    String fileUrl(String path) {
        "http://127.0.0.1:${http.address.port}/files/" + path
    }

    /** A file returned as a link, the way a server hands out a download (MCP resource_link). */
    static Map link(String uri, String name, String mimeType = null) {
        Map link = [type: "resource_link", uri: uri, name: name]
        if (mimeType) {
            link.mimeType = mimeType
        }
        link
    }

    private void download(HttpExchange exchange) {
        String path = exchange.requestURI.path.substring("/files/".length())
        downloadRequests << [path: path, authorization: exchange.requestHeaders.getFirst("Authorization")]
        Map file = downloads[path]
        if (file == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        if (file.location) {
            exchange.responseHeaders.add("Location", file.location as String)
        }
        if (file.type) {
            exchange.responseHeaders.add("Content-Type", file.type as String)
        }
        if (file.headerDelayMillis) {
            Thread.sleep(file.headerDelayMillis as long)
        }
        byte[] body = (file.body ?: new byte[0]) as byte[]
        exchange.sendResponseHeaders((file.status ?: 200) as int, body.length == 0 ? -1 : body.length)
        if (file.bodyDelayMillis) {
            exchange.responseBody.flush()
            Thread.sleep(file.bodyDelayMillis as long)
        }
        if (body.length > 0) {
            exchange.responseBody.withStream { it.write(body) }
        }
        exchange.close()
    }

    List<Map> calls() {
        received.findAll { it.method == "tools/call" }
    }

    void stop() {
        http.stop(0)
    }

    private void handle(HttpExchange exchange) {
        if (redirectTo) {
            exchange.responseHeaders.add("Location", redirectTo)
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
            return
        }
        Map message = ObjectMapper.getDefault().readValue(exchange.requestBody.text, Map)
        String authorization = exchange.requestHeaders.getFirst("Authorization")
        received << [method: message.method, params: message.params, authorization: authorization,
                     session: exchange.requestHeaders.getFirst("Mcp-Session-Id")]
        if (key && authorization != "Bearer " + key) {
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
            return
        }
        if (oauth && (refuseEveryToken || !oauth.valid(authorization))) {
            exchange.responseHeaders.add("WWW-Authenticate", challenge())
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
            return
        }
        if (message.method == "tools/call" && insufficientScope) {
            exchange.responseHeaders.add("WWW-Authenticate", 'Bearer error="insufficient_scope", scope="read write admin"')
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
            return
        }
        if (message.method == "tools/call" && callDelayMillis > 0) {
            Thread.sleep(callDelayMillis)
        }
        if (message.id == null) {
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
            return
        }
        Map result = switch (message.method) {
            case "initialize" -> [protocolVersion: "2025-06-18",
                                  capabilities: (prompts == null ? [tools: [:]] : [tools: [:], prompts: [:]])
                                          + (resources.isEmpty() && resourcePages.isEmpty() && templatePages.isEmpty()
                                          ? [:] : [resources: [:]]),
                                  serverInfo: [name: "fake", version: "1"]] + (instructions == null ? [:] : [instructions: instructions])
            case "resources/read" -> resources[(message.params as Map).uri as String] == null ? null
                    : [contents: [resources[(message.params as Map).uri as String]]]
            case "resources/list" -> resourcePages[((message.params as Map)?.cursor ?: "") as String] ?: [resources: []]
            case "resources/templates/list" -> templatePages[((message.params as Map)?.cursor ?: "") as String]
                    ?: [resourceTemplates: []]
            case "tools/list" -> listTools(message.params as Map)
            case "tools/call" -> callError ? null : answerCall(message.params as Map)
            case "prompts/list" -> prompts == null ? null : listPrompts(message.params as Map)
            case "prompts/get" -> promptError ? null : answerPrompt(message.params as Map)
            default -> null
        }
        Map error = message.method == "tools/call" && callError ? [code: -32603, message: callError]
                : message.method == "prompts/get" && promptError ? [code: -32603, message: promptError]
                : message.method == "resources/read" ? [code: -32002, message: "Resource not found"]
                : [code: -32601, message: "Unknown method"]
        Map answer = result == null
                ? [jsonrpc: "2.0", id: message.id, error: error]
                : [jsonrpc: "2.0", id: message.id, result: result]
        if (message.method == "initialize" && !stateless) {
            exchange.responseHeaders.add("Mcp-Session-Id", "session-1")
        }
        String body = ObjectMapper.getDefault().writeValueAsString(answer)
        if (eventStream) {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            body = "event: message\ndata: " + body + "\n\n"
        } else {
            exchange.responseHeaders.add("Content-Type", "application/json")
        }
        byte[] bytes = body.getBytes("UTF-8")
        exchange.sendResponseHeaders(result == null ? errorStatus : 200, bytes.length)
        exchange.responseBody.withStream { it.write(bytes) }
        exchange.close()
    }

    private String origin() {
        "http://127.0.0.1:${http.address.port}"
    }

    private String metadataPath() {
        metadataAtRoot ? "/.well-known/oauth-protected-resource" : "/.well-known/oauth-protected-resource/mcp"
    }

    private String challenge() {
        if (headerWithoutMetadata) {
            return 'Bearer realm="software"'
        }
        List<String> parameters = []
        if (headerNamesMetadata) {
            parameters << 'resource_metadata="' + (metadataUrlOverride ?: origin() + metadataPath()) + '"'
        }
        if (headerScope) {
            parameters << 'scope="' + headerScope + '"'
        }
        "Bearer " + parameters.join(", ")
    }

    /** RFC 9728's document - only where this shape serves it. */
    private void resourceMetadata(HttpExchange exchange) {
        metadataRequests << exchange.requestURI.path
        if (!oauth || headerWithoutMetadata || exchange.requestURI.path != metadataPath()) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        Map document = [resource: url(), authorization_servers: [oauth.issuer()]]
        if (metadataScopes) {
            document.scopes_supported = metadataScopes
        }
        byte[] bytes = ObjectMapper.getDefault().writeValueAsString(document).getBytes("UTF-8")
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.responseBody.withStream { it.write(bytes) }
        exchange.close()
    }

    /** A page after the first carries the tools under names of its own - a server never lists a tool twice. */
    private Map listTools(Map params) {
        String cursor = (params as Map)?.cursor as String
        String next = nextCursor(cursor)
        List<Map> page = cursor == null ? tools : tools.collect { it + [name: it.name + "_" + cursor] }
        next == null ? [tools: page] : [tools: page, nextCursor: next]
    }

    /** A file as an embedded resource, its content as base64 - the way an MCP server returns one. */
    static Map blob(String uri, String mimeType, byte[] content) {
        [type: "resource", resource: [uri: uri, mimeType: mimeType, blob: content.encodeBase64().toString()]]
    }

    /** A small ZIP archive with one entry. */
    static byte[] zip(String entry = "readme.txt", String text = "Hello") {
        def bytes = new ByteArrayOutputStream()
        new java.util.zip.ZipOutputStream(bytes).withCloseable { zip ->
            zip.putNextEntry(new java.util.zip.ZipEntry(entry))
            zip.write(text.getBytes("UTF-8"))
            zip.closeEntry()
        }
        bytes.toByteArray()
    }

    private Map listPrompts(Map params) {
        String cursor = (params as Map)?.cursor as String
        String next = nextPromptCursor(cursor)
        List<Map> page = cursor == null ? prompts : prompts.collect { it + [name: it.name + "_" + cursor] }
        next == null ? [prompts: page] : [prompts: page, nextCursor: next]
    }

    private Map answerPrompt(Map params) {
        if (promptMessages != null) {
            return [messages: promptMessages]
        }
        Map arguments = (params.arguments ?: [:]) as Map
        [description: "A test program",
         messages: [[role: "user", content: [type: "text", text: "Erstelle ein Prüfprogramm für ${arguments.package_name} im Projekt ${arguments.project_name}.".toString()]],
                    [role: "assistant", content: [type: "text", text: "Gern, ich lege es an."]],
                    [role: "user", content: [type: "image", data: "iVBORw0KGgo=", mimeType: "image/png"]],
                    [role: "user", content: [type: "text", text: "Anmerkung: ${arguments.comment ?: "keine"}".toString()]]]]
    }

    private Map answerCall(Map params) {
        Closure<Map> own = answers[params.name as String]
        if (own != null) {
            return own((params.arguments ?: [:]) as Map)
        }
        String source = (params.arguments as Map)?.source
        if (source != null && refusedSources.any { source.startsWith(it) }) {
            return [content: [[type: "text", text: "Booking of " + source + " refused"]], isError: true]
        }
        if (callContent != null) {
            return [content: callContent, isError: callIsError] + (callStructured == null ? [:]
                    : [structuredContent: callStructured]) + (callMeta == null ? [:] : [_meta: callMeta])
        }
        if (callText != null) {
            return [content: [[type: "text", text: callText]], isError: false]
        }
        String number = (params.arguments as Map)?.number
        if (params.name == "find_invoice") {
            return number == "0000"
                    ? [content: [[type: "text", text: "No invoice 0000"]], isError: true]
                    : [content: [[type: "text", text: "Invoice " + number + ": 412,50 EUR, paid"]], isError: false]
        }
        [content: [[type: "text", text: "Invoice " + number + " cancelled"]], isError: false]
    }
}
