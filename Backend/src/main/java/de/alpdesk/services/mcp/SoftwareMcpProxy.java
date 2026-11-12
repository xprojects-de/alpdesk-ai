package de.alpdesk.services.mcp;

import de.alpdesk.entities.mcp.McpAccessLevel;
import de.alpdesk.dto.mcp.McpPromptArgument;
import de.alpdesk.dto.mcp.McpPromptResponse;
import de.alpdesk.entities.mcp.McpConnection;
import de.alpdesk.entities.mcp.McpConnectionProtocol;
import de.alpdesk.entities.mcp.McpPrompt;
import de.alpdesk.entities.mcp.McpTool;
import de.alpdesk.repositories.mcp.McpConnectionRepository;
import de.alpdesk.repositories.mcp.McpPromptRepository;
import de.alpdesk.repositories.mcp.McpToolRepository;
import de.alpdesk.repositories.mcp.TenantMcpKeyRepository;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The connected software passed through the tenant's MCP server to MCP clients (docs/architecture/mcp.md "Connected
 * software through to MCP clients", roadmap point 41): the reading tools of the connections an MCP key may use and that
 * pass through ("Direkt an MCP-Clients") appear in the key's tools/list, and their calls go to the software as they
 * are - the client's model calls them itself, no second model answers. Where the key may change in a software (level
 * WRITE, roadmap point 68) its changing tools come too, marked as not read-only: whether a person is asked before
 * a call is then the MCP client's matter, by its own permission rules. Their software's instructions join the server's
 * in initialize, their turned-on prompts its prompts/list. {@link BrandedMcpServer} answers all of it; the SDK never
 * sees these tools and prompts.
 * <p>
 * What the software says goes on unchanged (decided 11 November 2026): a tool's entry as it listed it, a call's result,
 * a prompt's messages - Zirmo decides rights only. It changes a tool's name (the connection in front), the start of its
 * description and its readOnlyHint (the tenant admin's mark), resolves a link to a resource of the software's own -
 * only Zirmo is connected to it - and keeps the protocol's own _meta keys to itself.
 * <p>
 * The names are the chat's (`<connection>__<tool>`) but shorter: a client puts the server's name before them
 * (`mcp__muster-ai__Buchhaltung__find_invoice`) and the model's API takes at most 64 characters. They are computed anew
 * for every list and call, each from its own tool alone - so a later call reaches the tool listed, or none.
 */
@Singleton
public class SoftwareMcpProxy {

    /**
     * The longest passed-through tool name (decided). Claude Code shows `mcp__<server>__<tool>`: within 64 characters for
     * a server name of up to 25 - the frontend derives names of up to 40 (mcp.util.ts), whether Claude Code takes the
     * longer ones is still to be tried.
     */
    static final int MAX_NAME = 32;
    private static final Pattern PLAIN_PART = Pattern.compile("[A-Za-z0-9-]+(_[A-Za-z0-9-]+)*");
    /**
     * A tool not read again since V19 (no definition): read-only, or not read-only and nothing more - Claude Code repeats
     * a read-only tool by itself after a dropped connection and runs it in parallel, never a changing one.
     */
    private static final McpSchema.ToolAnnotations READ_ONLY = new McpSchema.ToolAnnotations(null, true, false, null, null, null);
    private static final McpSchema.ToolAnnotations CHANGING = new McpSchema.ToolAnnotations(null, false, null, null, null, null);
    /** The second label of a _meta key's prefix that MCP reserves for itself ("io.modelcontextprotocol/..."). */
    private static final Set<String> RESERVED_META_LABELS = Set.of("modelcontextprotocol", "mcp");
    private static final Logger LOG = LoggerFactory.getLogger(SoftwareMcpProxy.class);
    /** How a call fails after the software got it (McpClient's codes). */
    private static final Set<String> MAY_HAVE_RUN = Set.of("mcpServer.timeout", "mcpServer.requestFailed",
            "mcpServer.tooLarge");

    private final McpConnectionRepository connections;
    private final McpToolRepository tools;
    private final McpPromptRepository prompts;
    private final TenantMcpKeyRepository mcpKeys;
    private final McpConnectionService connectionService;
    private final McpClient client;
    private final McpJsonMapper json;
    private final JsonMapper storedJson;

    public SoftwareMcpProxy(McpConnectionRepository connections, McpToolRepository tools, McpPromptRepository prompts,
                            TenantMcpKeyRepository mcpKeys, McpConnectionService connectionService, McpClient client,
                            McpJsonMapper json, JsonMapper storedJson) {
        this.connections = connections;
        this.tools = tools;
        this.prompts = prompts;
        this.mcpKeys = mcpKeys;
        this.connectionService = connectionService;
        this.client = client;
        this.json = json;
        this.storedJson = storedJson;
    }

    /** A tool passed through: the name the client sees, its connection and the tool as the software has it. */
    record Passed(String name, McpConnection connection, McpTool tool) {
    }

    /** A prompt passed through, named like the tools. */
    record PassedPrompt(String name, McpConnection connection, McpPrompt prompt) {
    }

    /** Whether a tools/call is one of ours to answer - our own tools never carry "__". */
    static boolean isPassedThrough(String toolName) {
        return toolName.contains("__");
    }

    /** The connections the key may use that pass through, in the order of their names. */
    List<McpConnection> connectionsOf(Long tenantId, Long mcpKeyId) {
        return passing(tenantId, new HashSet<>(mcpKeys.findConnectionIds(mcpKeyId)));
    }

    private List<McpConnection> passing(Long tenantId, Set<Long> keyConnections) {
        List<McpConnection> passing = new ArrayList<>();
        for (McpConnection connection : connections.findByTenantIdOrderByName(tenantId)) {
            // An A2A agent has no tools to pass - its one tool is asked through ask_assistant.
            if (connection.passesThrough() && connection.protocol() == McpConnectionProtocol.MCP
                    && keyConnections.contains(connection.id())) {
                passing.add(connection);
            }
        }
        return passing;
    }

    /**
     * The tools turned on of the key's connections that pass through: the reading ones, the changing ones too where the
     * key may change in that software (WRITE).
     */
    List<Passed> passed(Long tenantId, Long mcpKeyId) {
        Map<Long, McpAccessLevel> levels = new HashMap<>();
        for (McpConnectionRepository.AccessRow row : mcpKeys.findConnectionLevels(mcpKeyId)) {
            levels.put(row.connectionId(), row.accessLevel());
        }
        List<Passed> passed = new ArrayList<>();
        for (McpConnection connection : passing(tenantId, levels.keySet())) {
            McpAccessLevel level = levels.get(connection.id());
            for (McpTool tool : tools.findByConnectionIdOrderByName(connection.id())) {
                if (McpTools.usable(tool, level)) {
                    passed.add(new Passed(name(connection.name(), tool.name(), tool.id()), connection, tool));
                }
            }
        }
        return passed;
    }

    /**
     * `<connection>__<tool>` as they are when both are plain names and fit within {@value #MAX_NAME} characters: the
     * pair is then the name, and no other tool can come to have it. Any other name is cut to fit - a long software name
     * before a long tool name, each keeps a part - and ends in "__" and the tool's (or prompt's) id. Not a counter by
     * position: turning on or off a tool before it would move the name to another tool, and a client holding the old
     * list - there is no tools/list_changed - would call that one with arguments meant for another. "__" is always in
     * the name: it tells a passed-through call from our own tools.
     */
    static String name(String connection, String tool, long id) {
        String plain = connection + "__" + tool;
        if (isPlain(connection) && isPlain(tool) && plain.length() <= MAX_NAME) {
            return plain;
        }
        String suffix = "__" + id;
        return within(McpTools.slug(connection), McpTools.slug(tool), MAX_NAME - suffix.length()) + suffix;
    }

    /**
     * Letters, digits and "-", "_" only between them: no "__" and no "_" at an end - so `<connection>__<tool>` splits
     * in one way only, and has "__" once where a cut name has it twice.
     */
    private static boolean isPlain(String part) {
        return PLAIN_PART.matcher(part).matches();
    }

    /** The software's part and the tool's part cut so that both and "__" fit into max - the tool keeps at least half. */
    private static String within(String connection, String tool, int max) {
        int room = max - 2;
        int toolLength = Math.min(tool.length(), Math.max(room - connection.length(), room / 2));
        int connectionLength = Math.min(connection.length(), room - toolLength);
        return connection.substring(0, connectionLength) + "__" + tool.substring(0, toolLength);
    }

    /** The key's passed-through tools as tools/list shows them. */
    public List<McpSchema.Tool> tools(Long tenantId, Long mcpKeyId) {
        List<McpSchema.Tool> listed = new ArrayList<>();
        for (Passed passed : passed(tenantId, mcpKeyId)) {
            listed.add(passed.tool().definition() == null ? asBefore(passed) : asListed(passed));
        }
        return listed;
    }

    /**
     * The tool's entry as the software listed it - title, annotations, _meta, output schema -, with our name, the
     * description after "[connection] " and readOnlyHint from the tenant admin's mark (else the software's own, which is
     * the same). Only the protocol's own _meta keys stay back.
     */
    private McpSchema.Tool asListed(Passed passed) {
        McpSchema.Tool own;
        try {
            own = json.readValue(passed.tool().definition(), McpSchema.Tool.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        boolean readOnly = !passed.tool().changes();
        McpSchema.ToolAnnotations given = own.annotations();
        McpSchema.ToolAnnotations annotations = given == null
                ? new McpSchema.ToolAnnotations(null, readOnly, null, null, null, null)
                : new McpSchema.ToolAnnotations(given.title(), readOnly, given.destructiveHint(), given.idempotentHint(),
                given.openWorldHint(), given.returnDirect());
        return new McpSchema.Tool(passed.name(), own.title(), described(passed.connection(), own.description()),
                own.inputSchema(), own.outputSchema(), annotations, passedMeta(own.meta()), own.icons());
    }

    /** A tool not read again since its entry is kept: its description and input schema, read-only or not. */
    private McpSchema.Tool asBefore(Passed passed) {
        return McpSchema.Tool.builder()
                .name(passed.name())
                .description(described(passed.connection(), passed.tool().description()))
                .inputSchema(json, passed.tool().inputSchema())
                .annotations(passed.tool().changes() ? CHANGING : READ_ONLY)
                .build();
    }

    /**
     * The _meta the software gave, without the keys MCP reserves for itself - a prefix whose second label is
     * "modelcontextprotocol" or "mcp" (io.modelcontextprotocol/...): they belong to the hop that sends them (decided 11
     * November 2026). Every other key goes on - anthropic/..., the software's own. Null stays null.
     */
    static @Nullable Map<String, Object> passedMeta(@Nullable Map<String, Object> meta) {
        if (meta == null) {
            return null;
        }
        Map<String, Object> passed = new LinkedHashMap<>();
        meta.forEach((key, value) -> {
            if (!isReservedMetaKey(key)) {
                passed.put(key, value);
            }
        });
        return passed;
    }

    private static boolean isReservedMetaKey(String key) {
        int slash = key.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String[] labels = key.substring(0, slash).split("\\.");
        return labels.length >= 2 && RESERVED_META_LABELS.contains(labels[1]);
    }

    /**
     * Calls the passed-through tool at its software with the client's arguments - the software checks them, as it does
     * for the chat. Its result goes back as the software sent it (McpClient.passTool: a link to a resource of its own
     * embedded as its file); a link left that names a resource of the software's own gets the connection in front where
     * the client may read the software's resources (decided 11 November 2026). A failure on the way is an error the
     * client's model reads, and so is a software out of reach. Empty for a name the key does not have (another key's
     * tool, a connection no longer passing through, a tool turned off, a changing tool the key may no longer use, a
     * connection renamed - the client lists again). A changing call is logged - the chat keeps who decided what, this is
     * its trace.
     */
    public Optional<McpSchema.CallToolResult> call(Long tenantId, Long mcpKeyId, String name,
                                                   Map<String, Object> arguments) {
        Optional<Passed> found = passed(tenantId, mcpKeyId).stream().filter(passed -> passed.name().equals(name)).findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        McpConnection connection = found.get().connection();
        McpTool tool = found.get().tool();
        if (tool.changes()) {
            LOG.info("MCP key {} of tenant {} calls the changing tool {} of {}", mcpKeyId, tenantId,
                    logged(tool.name()), logged(connection.name()));
        }
        McpSchema.CallToolResult result;
        try {
            result = client.passTool(connection.tenantId(), connection.name(), connection.baseUrl(),
                    connectionService.credential(connection), tool.name(), arguments);
        } catch (ServiceException e) {
            return Optional.of(McpSchema.CallToolResult.builder().addTextContent(failure(e, tool.changes()))
                    .isError(true).build());
        }
        return Optional.of(new McpSchema.CallToolResult(linksReadable(result.content(), connection), result.isError(),
                result.structuredContent(), passedMeta(result.meta())));
    }

    /**
     * The result's content, its links to resources of the software's own (those Zirmo did not embed) with the
     * connection in front where the client may read the software's resources - otherwise as it came. A link to a web
     * address is never touched.
     */
    private List<McpSchema.Content> linksReadable(List<McpSchema.Content> content, McpConnection connection) {
        if (!connection.passesResources() || content.stream().noneMatch(McpSchema.ResourceLink.class::isInstance)) {
            return content;
        }
        String part = SoftwareResources.parts(connections.findByTenantIdOrderByName(connection.tenantId()))
                .get(connection.id());
        List<McpSchema.Content> readable = new ArrayList<>();
        for (McpSchema.Content item : content) {
            readable.add(item instanceof McpSchema.ResourceLink link && link.uri() != null
                    ? new McpSchema.ResourceLink(link.name(), link.title(), SoftwareResources.prefixed(part, link.uri()),
                    link.description(), link.mimeType(), link.size(), link.annotations(), link.meta())
                    : item);
        }
        return readable;
    }

    /**
     * What the client's model is told of a failed call. A changing call that failed after it was sent - no answer in
     * time, an answer without content or too large - may have run at the software all the same: the model would repeat
     * it, so it is told to look first. Not for a software out of reach, a refused key or an error the software itself
     * answered with.
     */
    static String failure(ServiceException e, boolean changes) {
        if (changes && MAY_HAVE_RUN.contains(e.code())) {
            return e.getMessage() + " - the change may have been made; check before calling again";
        }
        return e.getMessage();
    }

    /** A name of the software's own in a log line: a control character in it (a line break) would forge a line. */
    static String logged(String name) {
        return name.replaceAll("\\p{Cntrl}", "?");
    }

    /**
     * What follows the server's own instructions in initialize for the key's passed-through software: one line naming
     * every one of them, then what each software says about its tools - each capped when read (2,000 characters); empty
     * without any software. The client may cut the whole (Claude Code reads 4,096 characters): ours come first, and the
     * line tells the client's model what exists and what to search for even where the software's own texts are cut
     * off - with many software it sees one or two of them only.
     */
    public String instructions(Long tenantId, Long mcpKeyId) {
        List<McpConnection> passing = connectionsOf(tenantId, mcpKeyId);
        if (passing.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n\nConnected software: ");
        text.append(String.join(", ", passing.stream().map(McpConnection::name).toList()))
                .append(" - their tools' names start with the software's name and \"__\", their descriptions with "
                        + "[<software>].");
        for (McpConnection connection : passing) {
            if (connection.instructions() != null) {
                text.append("\n\nSoftware ").append(connection.name()).append(" (its tools' descriptions start with [")
                        .append(connection.name()).append("]): ").append(connection.instructions());
            }
        }
        return text.toString();
    }

    /** The turned-on prompts of the key's connections that pass through, named like their tools. */
    List<PassedPrompt> passedPrompts(Long tenantId, Long mcpKeyId) {
        List<PassedPrompt> passed = new ArrayList<>();
        for (McpConnection connection : connectionsOf(tenantId, mcpKeyId)) {
            for (McpPrompt prompt : prompts.findByConnectionIdOrderByName(connection.id())) {
                if (prompt.enabled() && prompt.offered()) {
                    passed.add(new PassedPrompt(name(connection.name(), prompt.name(), prompt.id()), connection, prompt));
                }
            }
        }
        return passed;
    }

    /** The key's passed-through prompts as prompts/list shows them, with their arguments. */
    public List<McpSchema.Prompt> prompts(Long tenantId, Long mcpKeyId) {
        List<McpSchema.Prompt> listed = new ArrayList<>();
        for (PassedPrompt passed : passedPrompts(tenantId, mcpKeyId)) {
            List<McpSchema.PromptArgument> arguments = new ArrayList<>();
            for (McpPromptArgument argument : McpPromptResponse.arguments(passed.prompt().arguments(), storedJson)) {
                arguments.add(new McpSchema.PromptArgument(argument.name(), argument.description(), argument.required()));
            }
            listed.add(new McpSchema.Prompt(passed.name(), passed.prompt().title(), promptDescription(passed), arguments));
        }
        return listed;
    }

    /**
     * The software's prompt with the client's values as given - the software checks them - and its answer as it is:
     * every message, whatever its role or content (decided 11 November 2026; the chat's form keeps its own check,
     * SoftwarePrompts). Empty for a name the key does not have; 502 when the software fails.
     */
    public Optional<McpSchema.GetPromptResult> prompt(Long tenantId, Long mcpKeyId, String name,
                                                      @Nullable Map<String, Object> arguments) {
        Optional<PassedPrompt> found = passedPrompts(tenantId, mcpKeyId).stream()
                .filter(passed -> passed.name().equals(name)).findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        McpConnection connection = found.get().connection();
        McpSchema.GetPromptResult result = client.passPrompt(connection.tenantId(), connection.name(),
                connection.baseUrl(), connectionService.credential(connection), found.get().prompt().name(), arguments);
        return Optional.of(new McpSchema.GetPromptResult(result.description(), result.messages(),
                passedMeta(result.meta())));
    }

    private static String promptDescription(PassedPrompt passed) {
        return described(passed.connection(), passed.prompt().description());
    }

    /** The software's own description, after the name of the software it comes from - the client's model tells them apart. */
    static String described(McpConnection connection, @Nullable String own) {
        return "[" + connection.name() + "] " + (own == null ? "" : own.strip());
    }
}
