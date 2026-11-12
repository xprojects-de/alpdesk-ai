package de.alpdesk.services.ai

import io.micronaut.core.type.Argument
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.multipart.MultipartBody

/**
 * The HTTP calls the AI specs share: the admin sets up models, keys and tenants, a tenant user works with them. Each
 * call returns the parsed body, or the status when {@code status(...)} is used.
 */
class AiApi {

    static final String USER_PASSWORD = "geheim-genug-123"

    final HttpClient client
    final String adminToken

    AiApi(HttpClient client) {
        this.client = client
        this.adminToken = client.toBlocking().retrieve(
                HttpRequest.POST("/login", [username: "alpdesk", password: "alpdesk"]), Map).access_token
    }

    /** A chat model (prices 3 / 15 per million tokens) under a unique name; reads: e.g. [readsPdf: true]. */
    Map chatModel(String provider = "ANTHROPIC", Map reads = [:]) {
        admin("POST", "/admin/models", [provider: provider, kind: "CHAT", modelName: "chat-" + unique(),
                                        displayName: "Chat " + unique(), inputPrice: 3, outputPrice: 15] + reads)
    }

    /** An embedding model (64 dimensions, 0.02 per million tokens) under a unique name. */
    Map embeddingModel(int dimension = 64) {
        admin("POST", "/admin/models", [provider: "OPENAI", kind: "EMBEDDING", modelName: "embed-" + unique(),
                                        displayName: "Embedding " + unique(), dimension: dimension, inputPrice: 0.02,
                                        outputPrice: 0])
    }

    /** A Gemini image model (prices 0.30 / 30 per million tokens) under a unique name. */
    Map imageModel() {
        admin("POST", "/admin/models", [provider: "GEMINI", kind: "IMAGE", modelName: "image-" + unique(),
                                        displayName: "Bild " + unique(), inputPrice: 0.3, outputPrice: 30])
    }

    /** A reranker on a TEI server (0.01 per million tokens) under a unique name. */
    Map rerankModel() {
        admin("POST", "/admin/models", [provider: "TEI", kind: "RERANK", modelName: "rerank-" + unique(),
                                        displayName: "Reranker " + unique(), baseUrl: "https://reranker.example.com",
                                        inputPrice: 0.01, outputPrice: 0])
    }

    Map tenant(boolean globalKeysAllowed = false, String searchLanguage = null) {
        admin("POST", "/admin/tenants", [name: "Firma " + unique(), globalKeysAllowed: globalKeysAllowed,
                                         searchLanguage: searchLanguage])
    }

    /** The admin lets the tenant use these models, next to the ones it may use already. */
    void allow(Object tenantId, Map... models) {
        List ids = (admin("GET", "/admin/tenants/${tenantId}/models") as Map).modelIds as List
        admin("PUT", "/admin/tenants/${tenantId}/models", [modelIds: (ids + models*.id).unique()])
    }

    /** A user of the tenant, logged in: its token. It only chats. */
    String userToken(Object tenantId) {
        user(tenantId).token
    }

    /** A tenant admin of the tenant, logged in: its token. It keeps the settings (/tenant/admin/**) and chats. */
    String tenantAdminToken(Object tenantId) {
        user(tenantId, true).token
    }

    /**
     * A user of the tenant, logged in: [id: ..., username: ..., token: ...]. A plain user goes into the tenant's group
     * "Standard" (no rights - the chat and the company knowledge): without a group it would not get in.
     */
    Map user(Object tenantId, boolean tenantAdmin = false) {
        String username = "user-" + unique()
        Map created = admin("POST", "/admin/tenants/${tenantId}/users",
                [username: username, displayName: "Maria", password: USER_PASSWORD, admin: tenantAdmin,
                 groupIds: tenantAdmin ? [] : [standardGroup(tenantId)]]) as Map
        [id      : created.id,
         username: username,
         token   : client.toBlocking().retrieve(HttpRequest.POST("/login", [username: username, password: USER_PASSWORD]), Map).access_token]
    }

    /** The tenant's group "Standard" without rights - created the first time: its id. */
    Object standardGroup(Object tenantId) {
        def existing = (admin("GET", "/admin/tenants/${tenantId}/groups") as List<Map>).find { it.name == "Standard" }
        (existing ?: admin("POST", "/admin/tenants/${tenantId}/groups", [name: "Standard"]) as Map).id
    }

    /** A group of the tenant admin's tenant, under a unique name, with these members and knowledge areas. */
    Map group(String tenantAdminToken, List userIds, List areaIds = []) {
        call(tenantAdminToken, "POST", "/tenant/admin/groups", [name: "Gruppe " + unique(), userIds: userIds,
                                                                areaIds: areaIds]) as Map
    }

    /** Gives the user a level (READ, WRITE) for the connection through a group of its own: the group's id. */
    Object grant(String tenantAdminToken, Object connectionId, Object userId, String level) {
        def groupId = group(tenantAdminToken, [userId]).id
        setLevel(tenantAdminToken, groupId, connectionId, level)
        groupId
    }

    /**
     * Sets the group's level (READ, WRITE; null: no access) for the connection - in the group's own request, as its
     * dialog does, its other levels kept. basePath: the tenant admin's groups, or the admin's of a tenant.
     */
    void setLevel(String token, Object groupId, Object connectionId, String level, String basePath = "/tenant/admin/groups") {
        def group = (call(token, "GET", basePath) as List<Map>).find { it.id == groupId }
        def levels = (group.levels as List<Map>).findAll { it.connectionId != connectionId } +
                (level ? [[connectionId: connectionId, level: level]] : [])
        call(token, "PUT", "${basePath}/${groupId}", [name: group.name, levels: levels])
    }

    Object admin(String method, String path, Map body = null) {
        call(adminToken, method, path, body)
    }

    /** The parsed body of the call; token null: anonymous. */
    Object call(String token, String method, String path, Map body = null) {
        def request = HttpRequest.create(HttpMethod.valueOf(method), path)
        if (token) {
            request = request.bearerAuth(token)
        }
        if (body != null) {
            request = request.body(body)
        }
        client.toBlocking().retrieve(request, Argument.of(Object))
    }

    HttpStatus status(String token, String method, String path, Map body = null) {
        try {
            def request = HttpRequest.create(HttpMethod.valueOf(method), path)
            if (body != null || method in ["POST", "PUT"]) {
                request = request.body(body ?: [:])
            }
            if (token) {
                request = request.bearerAuth(token)
            }
            client.toBlocking().exchange(request, byte[]).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    /**
     * The page our OAuth callback sent the browser to ("<page>?connection=<id>&signIn=complete&<the server's answer>")
     * completes the sign-in with the token of whoever opened it, at connections ("/tenant/admin/mcp-connections" or the
     * admin's mirror) - the outcome as the page shows it: "<page>?connection=<id>&signIn=<ok or the error's code>".
     */
    String completeSignIn(String token, String connections, String page) {
        String marker = "&signIn=complete&"
        assert page.contains(marker): page
        String start = page.substring(0, page.indexOf(marker))
        String connectionId = start.substring(start.lastIndexOf("connection=") + "connection=".length())
        try {
            call(token, "POST", "${connections}/${connectionId}/sign-in/complete",
                    [callback: page.substring(page.indexOf(marker) + marker.length())])
            "${start}&signIn=ok"
        } catch (HttpClientResponseException e) {
            String code = e.response.getBody(Map).map { it.code as String }.orElse("oauth.unknown")
            "${start}&signIn=${code.substring(code.indexOf('.') + 1)}"
        }
    }

    /** The error message of a refused call. */
    String error(String token, String method, String path, Map body = null) {
        try {
            call(token, method, path, body)
            throw new AssertionError("$method $path was not refused")
        } catch (HttpClientResponseException e) {
            e.response.getBody(Map).map { it.message }.orElse("")
        }
    }

    /** Asks through the chat stream: its events in order, each [event: name, data: parsed JSON]. */
    List<Map> askEvents(String token, Map body) {
        events(token, "/tenant/chat/stream", body)
    }

    /** Runs ("run") or cancels ("cancel") a changing tool call the chat showed: the final event's ChatResponse. */
    Map decide(String token, Object actionId, String decision) {
        List<Map> events = events(token, "/tenant/chat/actions/${actionId}/${decision}", [:])
        assert events.last().event == "done", "the stream ended with ${events.last()}"
        events.last().data as Map
    }

    /** The events of a chat stream in order, each [event: name, data: parsed JSON]. */
    List<Map> events(String token, String path, Map body) {
        String stream = client.toBlocking().retrieve(HttpRequest.POST(path, body)
                .accept(MediaType.TEXT_EVENT_STREAM_TYPE, MediaType.APPLICATION_JSON_TYPE).bearerAuth(token), String)
        // A block of comments only (": keep-alive" while a tool runs) is no event - skipped, as every SSE client does.
        stream.split(/\n\n/).findAll { block -> block.readLines().any { it.trim() && !it.startsWith(":") } }.collect { block ->
            Map event = [:]
            block.readLines().each { line ->
                if (line.startsWith("event:")) {
                    event.event = line.substring(6).trim()
                } else if (line.startsWith("data:")) {
                    event.data = io.micronaut.serde.ObjectMapper.getDefault().readValue(line.substring(5).trim(), Object)
                }
            }
            event
        }
    }

    /** Asks through the chat stream: the final event's ChatResponse. */
    Map ask(String token, Map body) {
        List<Map> events = askEvents(token, body)
        assert events.last().event == "done", "the stream ended with ${events.last()}"
        events.last().data as Map
    }

    /** Adds files to a conversation (a new one when conversationId is null): name -> content. */
    Map chatUpload(String token, Map<String, byte[]> files, Object conversationId = null, String path = "/tenant/chat/files") {
        client.toBlocking().retrieve(chatUploadRequest(token, files, conversationId, path), Map)
    }

    HttpStatus chatUploadStatus(String token, Map<String, byte[]> files, Object conversationId = null, String path = "/tenant/chat/files") {
        try {
            client.toBlocking().exchange(chatUploadRequest(token, files, conversationId, path), byte[]).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    private static HttpRequest<?> chatUploadRequest(String token, Map<String, byte[]> files, Object conversationId, String path) {
        def body = MultipartBody.builder()
        files.each { name, content -> body.addPart("files", name, MediaType.APPLICATION_OCTET_STREAM_TYPE, content) }
        if (conversationId != null) {
            body.addPart("conversationId", conversationId.toString())
        }
        HttpRequest.POST(path, body.build()).contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(token)
    }

    /** The response of a download: status, headers and content. */
    io.micronaut.http.HttpResponse<byte[]> download(String token, String path) {
        def request = HttpRequest.GET(path)
        client.toBlocking().exchange(token ? request.bearerAuth(token) : request, byte[])
    }

    Map upload(String token, String fileName, String text, Object areaId = null) {
        def parts = MultipartBody.builder().addPart("file", fileName, MediaType.TEXT_PLAIN_TYPE, text.getBytes("UTF-8"))
        if (areaId != null) {
            parts.addPart("areaId", areaId.toString())
        }
        def body = parts.build()
        client.toBlocking().retrieve(HttpRequest.POST("/tenant/admin/documents", body)
                .contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(token), Map)
    }

    /** Uploads any file to the knowledge base (an image, a PDF): the document, or the refusal's status. */
    Object uploadFile(String token, String fileName, byte[] content, String path = "/tenant/admin/documents") {
        def body = MultipartBody.builder().addPart("file", fileName, MediaType.APPLICATION_OCTET_STREAM_TYPE, content).build()
        try {
            client.toBlocking().retrieve(HttpRequest.POST(path, body).contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
                    .bearerAuth(token), Map)
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    /** Imports a ZIP archive of these entries (path -> content): the import's files, or the refusal's status. */
    Object importArchive(String token, Map<String, byte[]> entries, String path = "/tenant/admin/documents/archive") {
        importArchiveBytes(token, zip(entries), path)
    }

    Object importArchiveBytes(String token, byte[] archive, String path = "/tenant/admin/documents/archive") {
        def body = MultipartBody.builder().addPart("file", "wissen.zip", MediaType.APPLICATION_OCTET_STREAM_TYPE, archive).build()
        try {
            (client.toBlocking().retrieve(HttpRequest.POST(path, body).contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
                    .bearerAuth(token), Map)).files
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    static byte[] zip(Map<String, byte[]> entries) {
        def out = new ByteArrayOutputStream()
        new java.util.zip.ZipOutputStream(out).withCloseable { zip ->
            entries.each { name, content ->
                zip.putNextEntry(new java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        out.toByteArray()
    }

    HttpStatus uploadStatus(String token, String fileName, String text) {
        try {
            upload(token, fileName, text)
            HttpStatus.CREATED
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    /** Waits until the document is embedded (or failed) - it happens in the background. Path: the list it is in. */
    Map awaitDocument(String token, Object documentId, String path = "/tenant/admin/documents") {
        long deadline = System.currentTimeMillis() + 15_000
        while (true) {
            Map document = (call(token, "GET", path) as List<Map>).find { it.id == documentId }
            if (document == null || document.status in ["READY", "FAILED"] || System.currentTimeMillis() > deadline) {
                return document
            }
            Thread.sleep(100)
        }
    }

    static String unique() {
        UUID.randomUUID().toString().substring(0, 8)
    }
}
