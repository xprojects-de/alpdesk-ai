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
import de.alpdesk.services.chat.ChatFileService;
import de.alpdesk.services.chat.ChatService;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
    private static final McpSchema.ToolAnnotations READ_ONLY = new McpSchema.ToolAnnotations(null, true, false, null, null, null);
    /**
     * Not read-only and nothing more: Claude Code repeats a read-only tool by itself after a dropped connection and
     * runs it in parallel - never a changing one. The software's own destructiveHint is not kept (mcp_tool keeps
     * readOnlyHint only).
     */
    private static final McpSchema.ToolAnnotations CHANGING = new McpSchema.ToolAnnotations(null, false, null, null, null, null);
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

    /** The key's passed-through tools as tools/list shows them: description and input schema as the software gives them. */
    public List<McpSchema.Tool> tools(Long tenantId, Long mcpKeyId) {
        List<McpSchema.Tool> listed = new ArrayList<>();
        for (Passed passed : passed(tenantId, mcpKeyId)) {
            listed.add(McpSchema.Tool.builder()
                    .name(passed.name())
                    .description(description(passed))
                    .inputSchema(json, passed.tool().inputSchema())
                    .annotations(passed.tool().changes() ? CHANGING : READ_ONLY)
                    .build());
        }
        return listed;
    }

    /**
     * Calls the passed-through tool at its software with the client's arguments - the software checks them, as it does
     * for the chat. Its text and its files go back as they are (files as embedded resources by the rules of
     * ask_assistant's: types from the list, text as text, at most 8 MB together); its error stays an error the client's
     * model reads, and so does a software out of reach. Empty for a name the key does not have (another key's tool, a
     * connection no longer passing through, a tool turned off, a changing tool the key may no longer use, a connection
     * renamed - the client lists again). A changing call is logged - the chat keeps who decided what, this is its trace.
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
        McpClient.CallResult result;
        try {
            result = client.callTool(connection.tenantId(), connection.name(), connection.baseUrl(),
                    connectionService.credential(connection), tool.name(), arguments);
        } catch (ServiceException e) {
            return Optional.of(McpSchema.CallToolResult.builder().addTextContent(failure(e, tool.changes()))
                    .isError(true).build());
        }
        if (result.error()) {
            return Optional.of(McpSchema.CallToolResult.builder().addTextContent(result.text()).isError(true).build());
        }
        return Optional.of(AssistantMcpTools.withFiles(result.text(), delivered(result.files())));
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
     * What the key's passed-through software says about its tools, to follow the server's own instructions in
     * initialize - each capped when read (2,000 characters); empty without any. The client may cut the whole (Claude
     * Code reads 4,096 characters): ours come first.
     */
    public String instructions(Long tenantId, Long mcpKeyId) {
        StringBuilder text = new StringBuilder();
        for (McpConnection connection : connectionsOf(tenantId, mcpKeyId)) {
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
     * The software's text of the prompt with the client's values - as the chat's form gets it (SoftwarePrompts), a
     * user message for the client's model. Empty for a name the key does not have; 400 for a missing required value or
     * one too long, 502 when the software fails.
     */
    public Optional<McpSchema.GetPromptResult> prompt(Long tenantId, Long mcpKeyId, String name,
                                                      @Nullable Map<String, Object> arguments) {
        Optional<PassedPrompt> found = passedPrompts(tenantId, mcpKeyId).stream()
                .filter(passed -> passed.name().equals(name)).findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> given = new HashMap<>();
        if (arguments != null) {
            arguments.forEach((key, value) -> given.put(key, value == null ? null : String.valueOf(value)));
        }
        McpPrompt prompt = found.get().prompt();
        Map<String, Object> values = SoftwarePrompts.values(McpPromptResponse.arguments(prompt.arguments(), storedJson), given);
        McpConnection connection = found.get().connection();
        String text = client.getPrompt(connection.tenantId(), connection.name(), connection.baseUrl(),
                connectionService.credential(connection), prompt.name(), values);
        return Optional.of(new McpSchema.GetPromptResult(promptDescription(found.get()),
                List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text)))));
    }

    private static String promptDescription(PassedPrompt passed) {
        String own = passed.prompt().description() == null ? "" : passed.prompt().description().strip();
        return "[" + passed.connection().name() + "] " + own;
    }

    /** The returned files as ask_assistant delivers them: a file beyond 8 MB together comes without content, named. */
    private static List<ChatService.DeliveredFile> delivered(List<McpClient.ReturnedFile> returned) {
        List<ChatService.DeliveredFile> delivered = new ArrayList<>();
        long total = 0;
        for (McpClient.ReturnedFile file : returned) {
            ChatService.DeliveredFile passedOn = ChatFileService.passedOn(file.name(), file.content(), file.mimeType());
            if (total + file.content().length > ChatService.MAX_DELIVERED_BYTES) {
                delivered.add(new ChatService.DeliveredFile(passedOn.name(), passedOn.contentType(), null, null));
                continue;
            }
            total += file.content().length;
            delivered.add(passedOn);
        }
        return delivered;
    }

    /** The software's own description, after the name of the software it comes from - the client's model tells them apart. */
    private static String description(Passed passed) {
        String own = passed.tool().description() == null ? "" : passed.tool().description().strip();
        return "[" + passed.connection().name() + "] " + own;
    }
}
