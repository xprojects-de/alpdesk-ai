package de.alpdesk.services.mcp;

import de.alpdesk.entities.mcp.McpAccessLevel;
import de.alpdesk.entities.mcp.McpConnection;
import de.alpdesk.entities.mcp.McpConnectionProtocol;
import de.alpdesk.entities.mcp.McpTool;
import de.alpdesk.repositories.mcp.McpConnectionRepository;
import de.alpdesk.repositories.mcp.McpToolRepository;
import de.alpdesk.repositories.mcp.TenantMcpKeyRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.a2a.A2aClient;
import de.alpdesk.services.a2a.AgentAnswers;
import de.alpdesk.services.chat.ChatFileService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.UserGroupService;
import de.alpdesk.services.tools.CreateFileTool;
import de.alpdesk.services.tools.ToolContext;
import de.alpdesk.services.tools.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The tools of the tenant's connected software a question may use (docs/architecture/mcp.md "MCP clients"): the tools a
 * tenant admin turned on - for a user, of the connections it has a level for: the reading ones with READ, the changing
 * ones too with WRITE (the chat asks the user before each of their calls); for a question over the tenant's MCP server
 * or from its client software, the reading ones of the connections a tenant admin opened to that asker. An A2A agent
 * (docs/architecture/a2a.md) is one tool, {@code <connection>__ask}, offered with any level - asking is reading.
 */
@Singleton
public class McpTools {

    /** The providers' longest tool name. */
    static final int MAX_NAME = 64;
    /**
     * The keys langchain4j takes on an object schema. With any other one on the top level - the Python SDK's (pydantic)
     * "title", the TypeScript SDK's (zod) "$schema" - it gives up on the whole schema, and the model would get a tool
     * without parameters. Nested properties with more keys stay as the server wrote them.
     */
    private static final Set<String> OBJECT_SCHEMA_KEYS =
            Set.of("type", "description", "properties", "required", "additionalProperties", "$defs");

    private final McpConnectionRepository connections;
    private final McpToolRepository tools;
    private final McpConnectionService connectionService;
    private final McpClient client;
    private final ChatFileService files;
    private final JsonMapper json;
    private final UserGroupService groups;
    private final TenantMcpKeyRepository mcpKeys;
    private final A2aClient a2a;
    private final AgentAnswers agentAnswers;
    private final TenantRepository tenants;

    public McpTools(McpConnectionRepository connections, McpToolRepository tools, McpConnectionService connectionService,
                    McpClient client, ChatFileService files, JsonMapper json, UserGroupService groups,
                    TenantMcpKeyRepository mcpKeys, A2aClient a2a, AgentAnswers agentAnswers, TenantRepository tenants) {
        this.connections = connections;
        this.tools = tools;
        this.connectionService = connectionService;
        this.client = client;
        this.files = files;
        this.json = json;
        this.groups = groups;
        this.mcpKeys = mcpKeys;
        this.a2a = a2a;
        this.agentAnswers = agentAnswers;
        this.tenants = tenants;
    }

    /** The one tool of an A2A agent, after the connection's name. */
    static final String ASK = "ask";

    /**
     * A tool of a connected software as the model sees it: its name carries the software's ("Buchhaltung__find_invoice")
     * - two connections may offer tools of the same name.
     */
    public record Offered(ToolSpecification specification, McpConnection connection, McpTool tool) {

        /** A changing tool: the chat asks the user before its call runs. */
        public boolean asksFirst() {
            return tool.changes();
        }
    }

    /** What the user's groups let it use; nothing without a user. */
    public List<Offered> forUser(Long tenantId, @Nullable Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<Offered> offered = new ArrayList<>();
        Set<String> names = new HashSet<>();
        boolean a2aAllowed = a2aAllowed(tenantId);
        for (McpConnectionRepository.AccessRow access : groups.accessOfUser(userId)) {
            McpConnection connection = connections.findByIdAndTenantId(access.connectionId(), tenantId).orElse(null);
            if (connection == null) {
                continue;
            }
            if (connection.protocol() == McpConnectionProtocol.A2A) {
                if (a2aAllowed) {
                    offered.add(agentTool(connection, names));
                }
                continue;
            }
            for (McpTool tool : tools.findByConnectionIdOrderByName(connection.id())) {
                if (usable(tool, access.accessLevel())) {
                    String name = uniqueName(connection.name(), tool.name(), names);
                    offered.add(new Offered(specification(name, connection, tool), connection, tool));
                }
            }
        }
        return offered;
    }

    /**
     * The connected software the user reaches through its groups, by name - what its questions may leave out (the
     * chips in the chat, roadmap point 51). The same path as {@link #forUser}: a membership the IDM gave or took shows
     * with the next request.
     */
    public List<McpConnection> connectionsOfUser(Long tenantId, Long userId) {
        Set<Long> reached = new HashSet<>();
        for (McpConnectionRepository.AccessRow access : groups.accessOfUser(userId)) {
            reached.add(access.connectionId());
        }
        return connections.findByTenantIdOrderByName(tenantId).stream()
                .filter(connection -> reached.contains(connection.id())).toList();
    }

    /** The connected software the client software's API key may use, by name - what its questions may leave out. */
    public List<McpConnection> connectionsOfApiKey(Long tenantId, Long apiKeyId) {
        Set<Long> keyConnections = new HashSet<>(connections.findConnectionIdsOfApiKey(apiKeyId));
        return connections.findByTenantIdOrderByName(tenantId).stream()
                .filter(connection -> keyConnections.contains(connection.id())).toList();
    }

    /**
     * What a question over the tenant's MCP server may use (ask_assistant): the reading tools turned on, of the
     * connections the MCP key it was asked with may use - never a changing one, nobody could be asked before it runs.
     * Nothing without a key.
     */
    public List<Offered> forMcpKey(Long tenantId, @Nullable Long mcpKeyId) {
        if (mcpKeyId == null) {
            return List.of();
        }
        Set<Long> keyConnections = new HashSet<>(mcpKeys.findConnectionIds(mcpKeyId));
        return readingTools(tenantId, connection -> keyConnections.contains(connection.id()));
    }

    /**
     * What the client software (/api/v1/chat, no user) may use: the reading tools turned on, of the connections its API
     * key may use (roadmap point 42) - never a changing one, nobody could be asked before it runs. Nothing without a key.
     */
    public List<Offered> forApiKey(Long tenantId, @Nullable Long apiKeyId) {
        if (apiKeyId == null) {
            return List.of();
        }
        Set<Long> keyConnections = new HashSet<>(connections.findConnectionIdsOfApiKey(apiKeyId));
        return readingTools(tenantId, connection -> keyConnections.contains(connection.id()));
    }

    /**
     * What a task's assistant prompt step may use: the reading tools of the connections its text names (A2A agents as
     * their one tool) - never a changing one, a task has no person to ask.
     */
    public List<Offered> forTask(Long tenantId, List<Long> connectionIds) {
        Set<Long> named = new HashSet<>(connectionIds);
        return readingTools(tenantId, connection -> named.contains(connection.id()));
    }

    private List<Offered> readingTools(Long tenantId, Predicate<McpConnection> opened) {
        List<Offered> offered = new ArrayList<>();
        Set<String> names = new HashSet<>();
        boolean a2aAllowed = a2aAllowed(tenantId);
        for (McpConnection connection : connections.findByTenantIdOrderByName(tenantId)) {
            if (!opened.test(connection)) {
                continue;
            }
            if (connection.protocol() == McpConnectionProtocol.A2A) {
                if (a2aAllowed) {
                    offered.add(agentTool(connection, names));
                }
                continue;
            }
            for (McpTool tool : tools.findByConnectionIdOrderByName(connection.id())) {
                if (usable(tool, McpAccessLevel.READ)) {
                    String name = uniqueName(connection.name(), tool.name(), names);
                    offered.add(new Offered(specification(name, connection, tool), connection, tool));
                }
            }
        }
        return offered;
    }

    /**
     * Calls the tool at the software. Its error, or a failure to reach it, is a text for the model. The files it returns
     * go below the answer like the ones create_file makes - where the answer keeps files.
     */
    public ToolResult execute(ToolContext context, Offered offered, ToolExecutionRequest request) {
        Map<String, Object> arguments = arguments(json, request.arguments());
        McpConnection connection = offered.connection();
        McpClient.CallResult result = connection.protocol() == McpConnectionProtocol.A2A
                ? askAgent(context, connection, arguments)
                : client.callTool(connection.tenantId(), connection.name(), connection.baseUrl(),
                connectionService.credential(connection), offered.tool().name(), arguments);
        StringBuilder text = new StringBuilder(result.text());
        for (McpClient.ReturnedFile file : result.files()) {
            // A failed call's file is not offered: the user would get a download instead of an answer.
            text.append("\n").append(result.error()
                    ? "[file \"" + ChatFileService.fileName(file.name()) + "\" returned with the error - not offered]"
                    : keep(context, connection, file));
        }
        appendTexts(text, result.files());
        // The software's name first: a general chat asks several topics, and a document's id holds only within one.
        String answer = "[" + connection.name() + "] " + text.toString().strip();
        return result.error() ? ToolResult.error(answer) : ToolResult.text(answer);
    }

    /**
     * The result without the "[<software>] " every result of the chat's software tools starts with - after the
     * "Error: " of a result the software marked as an error.
     */
    public static String withoutSoftwareName(String result, Offered offered) {
        String prefix = "[" + offered.connection().name() + "] ";
        if (result.startsWith(prefix)) {
            return result.substring(prefix.length());
        }
        String errorPrefix = "Error: " + prefix;
        return result.startsWith(errorPrefix) ? "Error: " + result.substring(errorPrefix.length()) : result;
    }

    /**
     * The text of the returned text files - code, a log, Markdown - for the model to read now, as clients such as Claude
     * Code show it: kept as a file, it could otherwise read it only with read_file from the next question on, and over
     * the MCP server never. Within the result's {@value McpClient#MAX_RESULT_TEXT} characters, like the tool's text.
     */
    private static void appendTexts(StringBuilder text, List<McpClient.ReturnedFile> returned) {
        for (McpClient.ReturnedFile file : returned) {
            String content = ChatFileService.textOfReturned(file.name(), file.content(), file.mimeType());
            if (content == null) {
                continue;
            }
            String name = ChatFileService.fileName(file.name());
            int room = McpClient.MAX_RESULT_TEXT - text.length();
            if (room < 1_000) {
                text.append("\n[the text of \"").append(name).append("\" left out - the result is long enough]");
                continue;
            }
            text.append("\n\n=== File \"").append(name).append("\" (returned by the tool) ===\n");
            text.append(content.length() > room ? content.substring(0, room) + "\n[cut off - the file is longer]" : content);
        }
    }

    /**
     * Stores a returned file for the answer (kept or sent with it) and tells the model what became of it. An MCP
     * software that answers only (passes nothing to MCP clients - docs/architecture/mcp.md) sends no file to the MCP
     * server's or A2A server's asker: its raw results stay inside, the answer is the model's. An A2A agent has no such
     * switch - its files are its answer.
     */
    private String keep(ToolContext context, McpConnection connection, McpClient.ReturnedFile file) {
        String name = ChatFileService.fileName(file.name());
        if (!context.takesFiles()) {
            return "[file \"" + name + "\" returned - files cannot be offered here]";
        }
        if (context.asker() == ToolContext.Asker.MCP_SERVER && connection.protocol() == McpConnectionProtocol.MCP
                && !connection.passesThrough()) {
            return "[file \"" + name + "\" returned - " + connection.name()
                    + " answers only: its files do not go to the asker]";
        }
        if (context.createdFilesExceptImages() >= CreateFileTool.MAX_FILES_PER_ANSWER) {
            return "[file \"" + name + "\" left out - at most " + CreateFileTool.MAX_FILES_PER_ANSWER + " files per answer]";
        }
        ChatFileService.PendingFile kept = files.createReturned(context.tenant(), file.name(), file.content(),
                file.mimeType(), context.files() == ToolContext.Files.KEPT);
        context.createdFiles().add(kept);
        return context.fileNote(kept.fileName());
    }

    /**
     * An A2A agent as one tool (docs/architecture/a2a.md "The tool"): a question, and one of the card's skills if the
     * model picks one. Its description is the card's - the agent's name and description - with the skills listed.
     */
    private Offered agentTool(McpConnection connection, Set<String> names) {
        List<McpTool> skills = tools.findByConnectionIdOrderByName(connection.id()).stream().filter(McpTool::offered)
                .toList();
        StringBuilder description = new StringBuilder("[" + connection.name() + "] Asks the A2A agent "
                + connection.name() + " a question and returns its answer.");
        if (connection.instructions() != null) {
            description.append(" ").append(connection.instructions());
        }
        if (!skills.isEmpty()) {
            description.append(" Its skills:");
            skills.forEach(skill -> description.append("\n- ").append(skill.name()).append(" (")
                    .append(skill.description()).append(")"));
        }
        JsonObjectSchema.Builder parameters = JsonObjectSchema.builder()
                .addStringProperty("question", "The question or task for the agent, complete in itself - it does not "
                        + "know this conversation")
                .required("question");
        if (!skills.isEmpty()) {
            parameters.addEnumProperty("skill", skills.stream().map(McpTool::name).toList(),
                    "The skill of the agent the question is for - optional");
        }
        String text = description.length() > McpClient.MAX_DESCRIPTION
                ? description.substring(0, McpClient.MAX_DESCRIPTION) : description.toString();
        ToolSpecification specification = ToolSpecification.builder()
                .name(uniqueName(connection.name(), ASK, names))
                .description(text)
                .parameters(parameters.build())
                .build();
        return new Offered(specification, connection,
                new McpTool(null, connection.id(), ASK, text, "{}", false, true, true, true, null));
    }

    /** Who asks goes with the question as at an answering agent - the user, the key or the task - its skill the one named. */
    private McpClient.CallResult askAgent(ToolContext context, McpConnection connection, Map<String, Object> arguments) {
        if (!(arguments.get("question") instanceof String question) || question.isBlank()) {
            throw ServiceException.badRequest("tool.questionRequired", "the question is required");
        }
        List<String> skills = arguments.get("skill") instanceof String named && !named.isBlank()
                ? List.of(named.strip()) : List.of();
        Map<String, Object> asker = agentAnswers.asker(context.tenant(), context.asker(), context.userId(),
                context.keyId(), skills);
        return a2a.ask(connection, connectionService.credential(connection), question, asker);
    }

    private boolean a2aAllowed(Long tenantId) {
        return tenants.findById(tenantId).map(tenant -> tenant.a2aAllowed()).orElse(false);
    }

    /** Turned on, still offered by the software - and a changing one only for a user who may change. */
    static boolean usable(McpTool tool, McpAccessLevel level) {
        return tool.enabled() && tool.offered() && (!tool.changes() || level == McpAccessLevel.WRITE);
    }

    private ToolSpecification specification(String name, McpConnection connection, McpTool tool) {
        return ToolSpecification.builder()
                .name(name)
                .description("[" + connection.name() + "] " + tool.description())
                .parameters(parameters(tool.inputSchema()))
                .build();
    }

    /** The server's JSON schema as langchain4j's - an object without properties when it is none. */
    private JsonObjectSchema parameters(String inputSchema) {
        try {
            Map<?, ?> schema = json.readValue(inputSchema, Map.class);
            JsonSchemaElement element = JsonSchemaElementJsonUtils.fromMap(objectSchema(schema));
            return element instanceof JsonObjectSchema object ? object : JsonObjectSchema.builder().build();
        } catch (IOException | RuntimeException e) {
            return JsonObjectSchema.builder().build();
        }
    }

    /** The schema's top level with only what langchain4j takes on an object ({@link #OBJECT_SCHEMA_KEYS}). */
    static Map<String, Object> objectSchema(Map<?, ?> schema) {
        Map<String, Object> object = new LinkedHashMap<>();
        schema.forEach((key, value) -> {
            // additionalProperties as a schema of its own is not taken either - only true or false.
            boolean taken = OBJECT_SCHEMA_KEYS.contains(String.valueOf(key))
                    && (!"additionalProperties".equals(key) || value instanceof Boolean);
            if (taken) {
                object.put(String.valueOf(key), value);
            }
        });
        return object;
    }

    /** The model's arguments as an object - anything else is a text for the model, not a failed answer. */
    static Map<String, Object> arguments(JsonMapper json, String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> map = json.readValue(text, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = map == null ? Map.of() : (Map<String, Object>) map;
            return arguments;
        } catch (IOException | RuntimeException e) {
            throw ServiceException.badRequest("tool.argumentsNoObject", "the arguments are no JSON object");
        }
    }

    /** "<software>__<tool>" with only the characters every provider takes, unique within one question. */
    static String uniqueName(String connection, String tool, Set<String> taken) {
        String base = slug(connection) + "__" + slug(tool);
        String name = base.length() > MAX_NAME ? base.substring(0, MAX_NAME) : base;
        for (int i = 2; !taken.add(name); i++) {
            String suffix = "_" + i;
            name = (base.length() + suffix.length() > MAX_NAME ? base.substring(0, MAX_NAME - suffix.length()) : base) + suffix;
        }
        return name;
    }

    static String slug(String text) {
        String slug = text.strip().replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_+", "_");
        return slug.isEmpty() ? "tool" : slug;
    }
}
