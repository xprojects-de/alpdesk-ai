package de.alpdesk.services.mcp;

import de.alpdesk.services.branding.BrandingService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.oauth.OAuthClient;
import de.alpdesk.services.tools.CreateFileTool;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Talks to a tenant's software through its MCP server (docs/architecture/mcp.md "MCP clients") with the MCP Java SDK's
 * client from micronaut-mcp-client-java-sdk: streamable HTTP, a session per use (initialize, then list the tools or call
 * one, then close) - no connection is kept between questions. The module builds its clients from configuration only
 * (micronaut.mcp.client.http.*); the tenants' connections are entered at runtime, so the client is built here the way
 * the module's factory builds it, with its JSON mapper and schema validator. The SDK keeps the tools' annotations:
 * readOnlyHint is what tells a reading tool from a changing one.
 * <p>
 * The server's address and key are the tenant's: a redirect is never followed (the key goes to the address entered,
 * nowhere else), the address is checked again before every call ({@link McpAddresses}), and a failure names the
 * connection, never the address. The client introduces itself with the tenant's brand - the name of the look shown for
 * the tenant (docs/architecture/branding.md) - so the software's logs name the assistant its customer knows.
 */
@Singleton
public class McpClient {

    /** The text of a tool result the model is given at most - a software may answer with a lot. */
    public static final int MAX_RESULT_TEXT = 100_000;
    /** Pages of tools read at most - a server that keeps sending a next page would otherwise hold the request forever. */
    static final int MAX_TOOL_PAGES = 100;
    /**
     * The limits of what a server may offer: every tool is stored and goes to the model with every question, and the
     * server is the tenant's - without them one listing could fill the memory the installation shares.
     */
    static final int MAX_TOOLS = 500;
    static final int MAX_TOOL_NAME = 128;
    static final int MAX_DESCRIPTION = 4_000;
    static final int MAX_SCHEMA = 20_000;
    /** A prompt's arguments at most - the user fills each in the chat's form. */
    static final int MAX_PROMPT_ARGUMENTS = 20;
    /**
     * One message of the server at most: a page of tools or a tool result - its text is cut to
     * {@value #MAX_RESULT_TEXT} anyway, its files (base64, a third larger) may have about 6 MB together. Not more: the
     * SDK reads an answer line by line through the JDK's LineSubscriberAdapter, which searches the whole line again for
     * every chunk - a file's base64 is one line, and the time grows with its square (measured 14 October 2026: 3 MB
     * file 3 s, 6 MB 12 s, 12 MB 48 s of a core). A too large answer costs that time until the limit is reached.
     */
    static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    /** The server's own error message at most, in our 502. */
    static final int MAX_ERROR_TEXT = 500;
    /** What the note of a link that gave no file says - a task's document takes it as its reason. */
    static final String NOT_FETCHED = "could not be fetched";

    private final McpJsonMapper json;
    private final JsonSchemaValidator schemaValidator;
    private final McpAddresses addresses;
    private final LinkDownloads downloads;
    private final BrandingService brandings;
    private final Duration timeout;
    private final String version;

    public McpClient(McpJsonMapper json, JsonSchemaValidator schemaValidator, McpAddresses addresses,
                     LinkDownloads downloads, BrandingService brandings, @Value("${app.ai.timeout}") Duration timeout,
                     @Value("${app.version}") String version) {
        this.json = json;
        this.schemaValidator = schemaValidator;
        this.addresses = addresses;
        this.downloads = downloads;
        this.brandings = brandings;
        this.timeout = timeout;
        this.version = version;
    }

    /** A tool as the server lists it. readOnly: its readOnlyHint - a tool without one counts as changing. */
    public record RemoteTool(String name, String description, Map<String, Object> inputSchema, boolean readOnly) {
    }

    /** A prompt as the server lists it, with its arguments - the user fills them in a form. */
    public record RemotePrompt(String name, @Nullable String title, String description, List<PromptArgument> arguments) {
    }

    public record PromptArgument(String name, String description, boolean required) {
    }

    /**
     * What a tool call gave: its text, whether the server reports it as an error, the files it returned - and whether
     * the text was longer than {@value #MAX_RESULT_TEXT} characters and cut there; {@code linkFailedForNow} when a link
     * gave no file for a reason the next try may not have (its server out of reach, too slow, failing).
     */
    public record CallResult(String text, boolean error, List<ReturnedFile> files, boolean cutOff,
                             boolean linkFailedForNow) {

        public CallResult(String text, boolean error, List<ReturnedFile> files, boolean cutOff) {
            this(text, error, files, cutOff, false);
        }
    }

    /**
     * A file a tool returned as an embedded resource: the name the last part of its uri gives (empty when it has
     * none), the type the server says, the content decoded.
     */
    public record ReturnedFile(String name, @Nullable String mimeType, byte[] content) {
    }

    /**
     * The tools a server offers, and its prompts - null with promptsError when they could not be read -, and the
     * instructions its initialize gave (null: none).
     */
    public record Offer(List<RemoteTool> tools, @Nullable List<RemotePrompt> prompts, @Nullable String promptsError,
                        @Nullable String instructions) {
    }

    /**
     * The server's tools, all pages of them. 502 when it cannot be reached, refuses the key, answers no MCP, keeps
     * sending pages (the same one again, or more than {@value #MAX_TOOL_PAGES}) or offers more than the limits allow.
     */
    public List<RemoteTool> listTools(Long tenantId, String name, String url, @Nullable Credential credential) {
        return session(tenantId, name, url, credential, mcp -> tools(mcp, name));
    }

    /**
     * The server's tools and prompts in one session - a connection's reading. Its tools as at {@link #listTools} (502
     * when they cannot be read); its prompts none when it offers no prompts (most servers offer tools only), and null
     * with the reason when they cannot be read - the tools are read all the same.
     */
    public Offer read(Long tenantId, String name, String url, @Nullable Credential credential) {
        return session(tenantId, name, url, credential, mcp -> {
            List<RemoteTool> tools = tools(mcp, name);
            String instructions = mcp.getServerInstructions();
            try {
                return new Offer(tools, prompts(mcp, name), null, instructions);
            } catch (RuntimeException e) {
                return new Offer(tools, null, failure(name, e, true, false).getMessage(), instructions);
            }
        });
    }

    private List<RemoteTool> tools(McpSyncClient mcp, String name) {
        List<RemoteTool> tools = new ArrayList<>();
        // A name twice cannot be stored (one row per name) - refused like any other offer the limits refuse.
        Set<String> names = new HashSet<>();
        Set<String> cursors = new HashSet<>();
        // Page by page: the SDK's listTools() without a cursor follows every next page itself, without end.
        String cursor = McpSchema.FIRST_PAGE;
        do {
            McpSchema.ListToolsResult page = mcp.listTools(cursor);
            for (McpSchema.Tool tool : page.tools()) {
                if (tools.size() >= MAX_TOOLS) {
                    throw gateway("mcpServer.tooManyTools", name, "offers more than " + MAX_TOOLS + " tools",
                            Map.of("max", String.valueOf(MAX_TOOLS)));
                }
                RemoteTool remote = remoteTool(name, tool);
                if (!names.add(remote.name())) {
                    throw gateway("mcpServer.toolTwice", name, "offers the tool " + remote.name() + " twice",
                            Map.of("tool", remote.name()));
                }
                tools.add(remote);
            }
            cursor = page.nextCursor() == null || page.nextCursor().isBlank() ? null : page.nextCursor();
            if (cursor != null && (!cursors.add(cursor) || cursors.size() >= MAX_TOOL_PAGES)) {
                throw gateway("mcpServer.endlessToolPages", name, "keeps sending more pages of tools");
            }
        } while (cursor != null);
        return tools;
    }

    /** Page by page, as the tools - the same limits, errors of their own. */
    private static List<RemotePrompt> prompts(McpSyncClient mcp, String name) {
        if (mcp.getServerCapabilities() == null || mcp.getServerCapabilities().prompts() == null) {
            return List.of();
        }
        List<RemotePrompt> prompts = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> cursors = new HashSet<>();
        String cursor = McpSchema.FIRST_PAGE;
        do {
            McpSchema.ListPromptsResult page = mcp.listPrompts(cursor);
            for (McpSchema.Prompt prompt : page.prompts()) {
                if (prompts.size() >= MAX_TOOLS) {
                    throw gateway("mcpServer.tooManyPrompts", name, "offers more than " + MAX_TOOLS + " prompts",
                            Map.of("max", String.valueOf(MAX_TOOLS)));
                }
                RemotePrompt remote = remotePrompt(name, prompt);
                if (!names.add(remote.name())) {
                    throw gateway("mcpServer.promptTwice", name, "offers the prompt " + remote.name() + " twice",
                            Map.of("prompt", remote.name()));
                }
                prompts.add(remote);
            }
            cursor = page.nextCursor() == null || page.nextCursor().isBlank() ? null : page.nextCursor();
            if (cursor != null && (!cursors.add(cursor) || cursors.size() >= MAX_TOOL_PAGES)) {
                throw gateway("mcpServer.endlessPromptPages", name, "keeps sending more pages of prompts");
            }
        } while (cursor != null);
        return prompts;
    }

    /**
     * The text the server makes of a prompt with the user's arguments: the text of its user messages - what goes into
     * the question's input field. Its other messages (an assistant's, images, resources) are left out. 502 as at
     * {@link #listTools}, also for a prompt without text.
     */
    public String getPrompt(Long tenantId, String name, String url, @Nullable Credential credential, String prompt,
                            Map<String, Object> arguments) {
        McpSchema.GetPromptResult result = session(tenantId, name, url, credential,
                mcp -> mcp.getPrompt(new McpSchema.GetPromptRequest(prompt, arguments)));
        if (result == null || result.messages() == null) {
            throw requestFailed(name);
        }
        StringBuilder text = new StringBuilder();
        for (McpSchema.PromptMessage message : result.messages()) {
            if (message.role() == McpSchema.Role.USER && message.content() instanceof McpSchema.TextContent content) {
                text.append(text.isEmpty() ? "" : "\n\n").append(content.text());
            }
        }
        if (text.toString().isBlank()) {
            throw gateway("mcpServer.promptWithoutText", name, "answered the prompt without a text");
        }
        return cut(text.toString().strip(), MAX_RESULT_TEXT);
    }

    /**
     * Calls the tool with the arguments the model gave. 502 as at {@link #listTools}. A file it returns as a link
     * (resource_link) is fetched at once - a temporary download expires in minutes: a uri of the server's own read with
     * resources/read in the same session, an https one downloaded after it ({@link LinkDownloads}). A link that gives
     * no file is a note for the model, never a failed call.
     */
    public CallResult callTool(Long tenantId, String name, String url, @Nullable Credential credential, String tool,
                               Map<String, Object> arguments) {
        Called called = session(tenantId, name, url, credential, mcp -> {
            McpSchema.CallToolResult answer = mcp.callTool(new McpSchema.CallToolRequest(tool, arguments));
            return new Called(answer, readServerLinks(mcp, answer));
        });
        McpSchema.CallToolResult result = called.result();
        if (result == null || result.content() == null) {
            throw requestFailed(name);
        }
        Set<McpSchema.ResourceLink> fetched = linksToFetch(result);
        StringBuilder text = new StringBuilder();
        List<ReturnedFile> files = new ArrayList<>();
        boolean linkFailedForNow = false;
        for (McpSchema.Content part : result.content()) {
            switch (part) {
                case McpSchema.TextContent content -> text.append(content.text()).append("\n");
                case McpSchema.EmbeddedResource embedded -> {
                    ReturnedFile file = returnedFile(embedded.resource());
                    if (file != null) {
                        files.add(file);
                    } else {
                        text.append("[file \"").append(fileName(embedded.resource().uri()))
                                .append("\" left out - its content is no valid base64]\n");
                    }
                }
                case McpSchema.ImageContent image -> {
                    ReturnedFile file = decoded("bild", image.mimeType(), image.data());
                    if (file != null) {
                        files.add(file);
                    } else {
                        text.append("[image left out - its content is no valid base64]\n");
                    }
                }
                case McpSchema.ResourceLink link -> {
                    String fileName = linkName(link);
                    if (link.uri() == null) {
                        text.append("[file \"").append(fileName).append("\" ").append(NOT_FETCHED)
                                .append(": the link has no address]\n");
                        continue;
                    }
                    if (!fetched.contains(link)) {
                        text.append("[file \"").append(fileName).append("\" not fetched - ").append(Boolean.TRUE.equals(
                                result.isError()) ? "the call failed" : "an answer takes at most "
                                + CreateFileTool.MAX_FILES_PER_ANSWER + " files").append("]\n");
                        continue;
                    }
                    Linked linked = isWebAddress(link.uri())
                            ? download(name, url, credential, link)
                            : called.serverLinks().get(link);
                    if (linked.file() != null) {
                        files.add(linked.file());
                    } else {
                        linkFailedForNow |= linked.forNow();
                        text.append("[file \"").append(fileName).append("\" ").append(NOT_FETCHED).append(": ")
                                .append(linked.reason()).append("]\n");
                    }
                }
                default -> text.append("[").append(part.type()).append(" content left out]\n");
            }
        }
        String answer = text.toString().strip();
        boolean cutOff = answer.length() > MAX_RESULT_TEXT;
        if (cutOff) {
            answer = answer.substring(0, MAX_RESULT_TEXT) + "\n[cut off - the result was longer]";
        }
        return new CallResult(answer, Boolean.TRUE.equals(result.isError()), files, cutOff, linkFailedForNow);
    }

    /** What a tool call answered, with what its links of the server's own gave - read while the session was open. */
    private record Called(McpSchema.CallToolResult result, Map<McpSchema.ResourceLink, Linked> serverLinks) {
    }

    /** A link's file, or why there is none. */
    private record Linked(@Nullable ReturnedFile file, @Nullable String reason, boolean forNow) {

        Linked(@Nullable ReturnedFile file, @Nullable String reason) {
            this(file, reason, false);
        }
    }

    /**
     * The links worth fetching: none of a failed call (its files are not offered), and only as many as this result
     * leaves room for among an answer's files - counted per call: a later call of the same chat answer may still fetch
     * one that McpTools.keep then leaves out (docs/architecture/mcp.md "Files returned as a link").
     */
    private static Set<McpSchema.ResourceLink> linksToFetch(@Nullable McpSchema.CallToolResult result) {
        Set<McpSchema.ResourceLink> links = new HashSet<>();
        if (result == null || result.content() == null || Boolean.TRUE.equals(result.isError())) {
            return links;
        }
        int files = 0;
        for (McpSchema.Content part : result.content()) {
            if (part instanceof McpSchema.EmbeddedResource || part instanceof McpSchema.ImageContent) {
                files++;
            } else if (part instanceof McpSchema.ResourceLink link && link.uri() != null
                    && files < CreateFileTool.MAX_FILES_PER_ANSWER) {
                links.add(link);
                files++;
            }
        }
        return links;
    }

    /** The links of the server's own read with resources/read - inside the session, where such a uri lives. */
    private static Map<McpSchema.ResourceLink, Linked> readServerLinks(McpSyncClient mcp,
                                                                      McpSchema.CallToolResult result) {
        Map<McpSchema.ResourceLink, Linked> read = new HashMap<>();
        for (McpSchema.ResourceLink link : linksToFetch(result)) {
            if (!isWebAddress(link.uri())) {
                read.put(link, readResource(mcp, link));
            }
        }
        return read;
    }

    private static Linked readResource(McpSyncClient mcp, McpSchema.ResourceLink link) {
        McpSchema.ReadResourceResult answer;
        try {
            answer = mcp.readResource(new McpSchema.ReadResourceRequest(link.uri()));
        } catch (RuntimeException e) {
            return new Linked(null, "the server refused to read it" + refusal(e));
        }
        if (answer == null || answer.contents() == null || answer.contents().isEmpty()) {
            return new Linked(null, "the server sent no content for it");
        }
        ReturnedFile file = returnedFile(answer.contents().getFirst());
        if (file == null) {
            return new Linked(null, "its content is no valid base64");
        }
        return new Linked(new ReturnedFile(linkName(link), file.mimeType() != null ? file.mimeType() : link.mimeType(),
                file.content()), null);
    }

    /** The server's error, or the SDK's refusal of a server that offers no resources - empty for any other cause. */
    private static String refusal(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpError || cause instanceof IllegalStateException) {
                return " - " + cut(String.valueOf(cause.getMessage()), MAX_ERROR_TEXT);
            }
        }
        return "";
    }

    /** The tool has run when its links are fetched: whatever goes wrong here is a note, never a failed call. */
    private Linked download(String name, String url, @Nullable Credential credential, McpSchema.ResourceLink link) {
        try {
            String bearer = credential == null || credential.token() == null ? null : credential.token().get();
            LinkDownloads.Download download = downloads.fetch(name, url, link.uri(), bearer);
            return new Linked(new ReturnedFile(linkName(link),
                    link.mimeType() != null ? link.mimeType() : download.contentType(), download.content()), null);
        } catch (LinkDownloads.NotFetched e) {
            return new Linked(null, e.getMessage(), e.forNow());
        } catch (ServiceException e) {
            return new Linked(null, e.getMessage());
        }
    }

    private static boolean isWebAddress(String uri) {
        String lower = uri.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    /** The link's name, else the last part of its uri. */
    private static String linkName(McpSchema.ResourceLink link) {
        return link.name() != null && !link.name().isBlank() ? link.name().strip() : fileName(link.uri());
    }

    /**
     * The notes of links that gave no file, from a call's text - for a task's document that got none, so its reason
     * says why. Empty when there are none.
     */
    public static String unfetchedNotes(CallResult result) {
        StringBuilder notes = new StringBuilder();
        for (String line : result.text().split("\n")) {
            if (line.startsWith("[file \"") && line.contains(NOT_FETCHED)) {
                notes.append(notes.isEmpty() ? "" : " ").append(line);
            }
        }
        return notes.toString();
    }

    /**
     * The file an embedded resource carries, as base64 (blob) or as text - null for a blob that is no base64. Its size
     * needs no check of its own: the whole result is at most {@value #MAX_MESSAGE_BYTES} bytes. An image (MCP's image
     * content) has no name: "bild", its extension added from its type when it is stored.
     */
    private static @Nullable ReturnedFile returnedFile(McpSchema.ResourceContents resource) {
        String name = fileName(resource.uri());
        return switch (resource) {
            case McpSchema.TextResourceContents text -> new ReturnedFile(name,
                    text.mimeType() == null ? "text/plain" : text.mimeType(), text.text().getBytes(StandardCharsets.UTF_8));
            case McpSchema.BlobResourceContents blob -> decoded(name, blob.mimeType(), blob.blob());
            default -> null;
        };
    }

    private static @Nullable ReturnedFile decoded(String name, @Nullable String mimeType, @Nullable String base64) {
        try {
            return base64 == null ? null : new ReturnedFile(name, mimeType, Base64.getDecoder().decode(base64));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The last part of a resource's uri ("file:///demo-project.zip" - "demo-project.zip"); empty when it has none. */
    static String fileName(@Nullable String uri) {
        if (uri == null) {
            return "";
        }
        String path = uri;
        try {
            String decoded = URI.create(uri).getPath();
            path = decoded != null ? decoded : uri;
        } catch (IllegalArgumentException e) {
            // Not a valid URI - its text up to a query or a fragment: a signed link's signature is no name.
            path = uri.split("[?#]", 2)[0];
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * One session: initialize, the use, close. The bearer is taken once and sent with every request. A signed-in
     * connection answered 401 renews its token once and the session is repeated once - a second 401 marks the sign-in
     * lost, a renewal refused marks it already. Done here, not by the SDK's authorization handler: its retry sends the request as it was built
     * before the renewal, with the old token.
     */
    private <T> T session(Long tenantId, String name, String url, @Nullable Credential credential,
                          Function<McpSyncClient, T> use) {
        addresses.requireAllowed(url, name);
        Credential sending = credential == null ? Credential.NONE : credential;
        String token = sending.token() == null ? null : sending.token().get();
        try {
            return attempt(tenantId, name, url, token, sending.signedIn(), use);
        } catch (TokenRefused refused) {
            String renewed = sending.renew().apply(token);
            try {
                return attempt(tenantId, name, url, renewed, true, use);
            } catch (TokenRefused again) {
                sending.signInLost().run();
                throw again;
            }
        }
    }

    private <T> T attempt(Long tenantId, String name, String url, @Nullable String token, boolean signedIn,
                          Function<McpSyncClient, T> use) {
        McpSyncClient mcp = io.modelcontextprotocol.client.McpClient.sync(transport(url, token))
                .jsonSchemaValidator(schemaValidator)
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .clientInfo(new McpSchema.Implementation(brandings.forTenant(tenantId).name(), version))
                .build();
        boolean initialized = false;
        try {
            mcp.initialize();
            initialized = true;
            return use.apply(mcp);
        } catch (RuntimeException e) {
            throw failure(name, e, initialized, signedIn);
        } finally {
            mcp.close();
        }
    }

    /** The SDK takes the server's origin and the path apart; no redirects, the bearer only to this server. */
    private HttpClientStreamableHttpTransport transport(String url, @Nullable String token) {
        URI uri = URI.create(url);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder(uri.getScheme() + "://" + uri.getRawAuthority())
                .endpoint(path)
                .clientBuilder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER))
                .connectTimeout(Duration.ofSeconds(10))
                .maxResponseSize(MAX_MESSAGE_BYTES)
                .jsonMapper(json);
        if (token != null) {
            builder.httpRequestCustomizer((request, method, endpoint, body, context) ->
                    request.header("Authorization", "Bearer " + token));
        }
        return builder.build();
    }

    private RemoteTool remoteTool(String name, McpSchema.Tool tool) {
        if (tool.name().length() > MAX_TOOL_NAME) {
            throw gateway("mcpServer.toolNameTooLong", name,
                    "offers a tool with a name longer than " + MAX_TOOL_NAME + " characters",
                    Map.of("max", String.valueOf(MAX_TOOL_NAME)));
        }
        if (schemaLength(tool) > MAX_SCHEMA) {
            throw gateway("mcpServer.toolSchemaTooLong", name,
                    "offers a tool with an input schema longer than " + MAX_SCHEMA + " characters",
                    Map.of("max", String.valueOf(MAX_SCHEMA)));
        }
        String description = tool.description() == null ? "" : cut(tool.description(), MAX_DESCRIPTION);
        Map<String, Object> schema = tool.inputSchema() == null ? Map.of("type", "object")
                : json.convertValue(tool.inputSchema(), new io.modelcontextprotocol.json.TypeRef<>() {
                });
        boolean readOnly = tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
        return new RemoteTool(tool.name(), description, schema, readOnly);
    }

    /** A prompt within the limits of a tool: its name, its description, its arguments (a prompt's own limit). */
    private static RemotePrompt remotePrompt(String name, McpSchema.Prompt prompt) {
        if (prompt.name().length() > MAX_TOOL_NAME) {
            throw gateway("mcpServer.promptNameTooLong", name,
                    "offers a prompt with a name longer than " + MAX_TOOL_NAME + " characters",
                    Map.of("max", String.valueOf(MAX_TOOL_NAME)));
        }
        List<PromptArgument> arguments = new ArrayList<>();
        for (McpSchema.PromptArgument argument : prompt.arguments() == null ? List.<McpSchema.PromptArgument>of()
                : prompt.arguments()) {
            if (arguments.size() >= MAX_PROMPT_ARGUMENTS || argument.name().length() > MAX_TOOL_NAME) {
                throw gateway("mcpServer.promptArguments", name, "offers a prompt with more than "
                        + MAX_PROMPT_ARGUMENTS + " arguments or too long a name of one",
                        Map.of("max", String.valueOf(MAX_PROMPT_ARGUMENTS)));
            }
            arguments.add(new PromptArgument(argument.name(),
                    argument.description() == null ? "" : cut(argument.description(), MAX_DESCRIPTION),
                    Boolean.TRUE.equals(argument.required())));
        }
        return new RemotePrompt(prompt.name(), prompt.title() == null ? null : cut(prompt.title(), MAX_TOOL_NAME),
                prompt.description() == null ? "" : cut(prompt.description(), MAX_DESCRIPTION), arguments);
    }

    private int schemaLength(McpSchema.Tool tool) {
        try {
            return tool.inputSchema() == null ? 0 : json.writeValueAsString(tool.inputSchema()).length();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) + " [cut off]" : text;
    }

    /**
     * What went wrong, in words for the tenant admin - the SDK wraps the cause in its own exceptions. A server that
     * answered initialize is an MCP server: what fails after it is the request, never "not MCP".
     */
    private ServiceException failure(String name, RuntimeException e, boolean initialized, boolean signedIn) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            switch (cause) {
                case ServiceException service -> {
                    return service;
                }
                case McpHttpClientTransportAuthorizationException refused -> {
                    // A 403 asks for more rights (step-up) - a new token of the same sign-in brings none.
                    if (signedIn) {
                        return refused.getResponseInfo() != null && refused.getResponseInfo().statusCode() == 401
                                ? new TokenRefused(name) : OAuthClient.signInNeeded(name);
                    }
                    return new KeyRefused(name, refused.getResponseInfo() == null ? null
                            : refused.getResponseInfo().headers().firstValue("WWW-Authenticate").orElse(null));
                }
                case HttpConnectTimeoutException _, ConnectException _ -> {
                    return gateway("mcpServer.unreachable", name, "is not reachable");
                }
                case HttpTimeoutException _, TimeoutException _ -> {
                    return gateway("mcpServer.timeout", name, "did not answer in time");
                }
                case McpError error -> {
                    return answeredWithError(name, String.valueOf(error.getMessage()));
                }
                case McpTransportException tooLarge when String.valueOf(tooLarge.getMessage())
                        .contains("exceeds the maximum allowed size") -> {
                    String max = String.valueOf(MAX_MESSAGE_BYTES / (1024 * 1024));
                    return gateway("mcpServer.tooLarge", name, "sent an answer larger than " + max + " MB",
                            Map.of("max", max));
                }
                default -> {
                    String refusal = errorSentWithHttpStatus(cause.getMessage());
                    if (refusal != null) {
                        return answeredWithError(name, refusal);
                    }
                }
            }
        }
        if (initialized) {
            return requestFailed(name);
        }
        return gateway("mcpServer.notMcp", name, "did not answer as an MCP server");
    }

    /**
     * Also for an answer the SDK handed back empty: on an HTTP 400 to a request with a session id it takes the session
     * for unknown, ends it and completes the request without a result - racing its own dismissal of the request, which
     * would have failed it with an exception.
     */
    private static ServiceException requestFailed(String name) {
        return gateway("mcpServer.requestFailed", name, "could not carry out the request - e.g. a value it does not accept");
    }

    private static ServiceException answeredWithError(String name, String message) {
        String text = cut(message, MAX_ERROR_TEXT);
        return gateway("mcpServer.error", name, "answered with an error: " + text, Map.of("error", text));
    }

    /**
     * The message of a JSON-RPC error a server sent with HTTP 400 or 500 (servers on the MCP Java SDK do, e.g. for a
     * prompt's value they refuse): the SDK does not take it for an error answer but puts the body into the message of a
     * transport exception ("... data={"jsonrpc":"2.0","id":...,"error":{...}}"). Null when there is none.
     */
    @Nullable
    private String errorSentWithHttpStatus(@Nullable String exceptionMessage) {
        int start = exceptionMessage == null ? -1 : exceptionMessage.indexOf("data={");
        if (start < 0) {
            return null;
        }
        int end = exceptionMessage.lastIndexOf('}');
        if (end < start) {
            return null;
        }
        try {
            Map<?, ?> answer = json.readValue(exceptionMessage.substring(start + "data=".length(), end + 1), Map.class);
            return answer.get("error") instanceof Map<?, ?> error && error.get("message") instanceof String message
                    ? message : null;
        } catch (RuntimeException | IOException notJson) {
            return null;
        }
    }

    /** A signed-in connection's token was refused (401): renewed once, the session repeated once. */
    private static final class TokenRefused extends ServiceException {

        TokenRefused(String name) {
            super(HttpStatus.BAD_GATEWAY, "oauth.signInNeeded", name + ": needs a sign-in - sign in on the page Software",
                    Map.of("name", name));
        }
    }

    /**
     * The server refused the call with 401: "refused our key" - with the WWW-Authenticate header it sent, in which a
     * server that signs in with OAuth names its resource metadata (docs/architecture/mcp.md "Signing in to the
     * software's server").
     */
    public static final class KeyRefused extends ServiceException {

        private final String wwwAuthenticate;

        KeyRefused(String name, @Nullable String wwwAuthenticate) {
            super(HttpStatus.BAD_GATEWAY, "mcpServer.keyRefused", name + " refused our key - check it in the connection",
                    Map.of("name", name));
            this.wwwAuthenticate = wwwAuthenticate;
        }

        public @Nullable String wwwAuthenticate() {
            return wwwAuthenticate;
        }
    }

    private static ServiceException gateway(String code, String name, String what) {
        return gateway(code, name, what, Map.of());
    }

    private static ServiceException gateway(String code, String name, String what, Map<String, String> values) {
        Map<String, String> params = new HashMap<>(values);
        params.put("name", name);
        return new ServiceException(HttpStatus.BAD_GATEWAY, code, name + " " + what, params);
    }
}
