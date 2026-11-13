package de.alpdesk.services.mcp;

import de.alpdesk.entities.assistant.ChatTemplate;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.chat.ChatService;
import de.alpdesk.services.chat.SentFile;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tools.ToolContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.mcp.server.context.MicronautMcpTransportContext;
import io.micronaut.security.authentication.Authentication;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The tools of the tenant's MCP server (docs/architecture/mcp.md): an MCP client such as Claude Code asks the tenant's
 * assistant, like a user who sees no documents - it gets answers, never documents; its own search and reading are
 * {@link KnowledgeMcpTools}. Micronaut MCP serves them at /mcp (stateless streamable HTTP); the MCP key's
 * authentication, which names the tenant, comes with the transport context.
 * Sync tools run off the event loop - the SDK hands them to a worker thread.
 * <p>
 * Tool specifications rather than {@code @Tool} methods: their input schemas are written out here, flat and without
 * references, so every client takes them - Micronaut's generated schemas give a list's items as a reference to a URL
 * no client can reach.
 */
@Factory
public class AssistantMcpTools {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantMcpTools.class);
    static final String ASK_ASSISTANT = "ask_assistant";
    static final String LIST_TEMPLATES = "list_templates";
    /** How the descriptions name the assistant before the tenant is known - BrandedMcpServer puts in its brand. */
    private static final String UNBRANDED = "the company's assistant";
    private static final McpSchema.ToolAnnotations READ_ONLY = new McpSchema.ToolAnnotations(null, true, false, null, null, null);
    private static final String ASK_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "question": {"type": "string", "description": "The complete question"},
                "template": {"type": "string", "description": "Optional: the name of a template (see list_templates) - its instructions and settings shape the answer"},
                "files": {
                  "type": "array",
                  "description": "Optional: text files the question is about, e.g. source code to check against the company's rules",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": {"type": "string", "description": "The file's name, e.g. src/Control.java"},
                      "content": {"type": "string", "description": "The file's text"}
                    },
                    "required": ["name", "content"]
                  }
                }
              },
              "required": ["question"]
            }""";
    private static final String NO_ARGUMENTS_SCHEMA = """
            {"type": "object", "properties": {}}""";

    private final ChatService chat;
    private final AssistantService assistants;
    private final McpJsonMapper json;

    public AssistantMcpTools(ChatService chat, AssistantService assistants, McpJsonMapper json) {
        this.chat = chat;
        this.assistants = assistants;
        this.json = json;
    }

    @Singleton
    SyncToolSpecification askAssistant() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(ASK_ASSISTANT)
                .description(askDescription(UNBRANDED))
                .inputSchema(json, ASK_SCHEMA)
                .annotations(READ_ONLY)
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler(guarded(ASK_ASSISTANT, this::ask)).build();
    }

    @Singleton
    SyncToolSpecification listTemplates() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(LIST_TEMPLATES)
                .description(listDescription(UNBRANDED))
                .inputSchema(json, NO_ARGUMENTS_SCHEMA)
                .annotations(READ_ONLY)
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(guarded(LIST_TEMPLATES, (context, request) -> text(templateList(tenantId(context)), false)))
                .build();
    }

    /**
     * A tool's handler whose unexpected failure - the database briefly out of reach, a provider's key no longer
     * decryptable - is a tool error too, like a refusal. Thrown on, the SDK makes it a JSON-RPC error with the
     * exception's text, the transport sends that with HTTP 500, and the MCP SDK's client ends its whole session
     * (docs/architecture/mcp.md); nothing of it reached our log.
     */
    static BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> guarded(
            String name, BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        return (context, request) -> {
            try {
                return handler.apply(context, request);
            } catch (RuntimeException e) {
                // The exception's class only: a database error's text may carry a row's values (CLAUDE.md "Security").
                LOG.warn("The MCP tool {} of tenant {} failed: {}", name, tenantId(context), e.getClass().getSimpleName());
                return text("The tool " + name + " failed - try again later", true);
            }
        };
    }

    /**
     * The assistant's answer with its sources. A refusal (no default model, an unknown template, files too large, a
     * failing provider) is a tool error the client's model reads - not a protocol error; so are arguments that do not
     * match the schema, which the SDK refuses itself.
     */
    private McpSchema.CallToolResult ask(McpTransportContext context, McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        if (!(arguments.get("question") instanceof String question) || question.isBlank()) {
            return text("The question is required.", true);
        }
        Long tenantId = tenantId(context);
        try {
            Long templateId = templateId(tenantId, arguments.get("template") instanceof String name ? name : null);
            ChatService.Answer answer = chat.answerOnce(tenantId, ToolContext.Asker.MCP_SERVER, mcpKeyId(context), null,
                    templateId, question,
                    sentFiles(arguments.get("files")), null, null);
            String sources = answer.sources().isEmpty() ? "" : "\n\nSources: " + String.join(", ", answer.sources());
            return withFiles(answer.text() + sources, answer.files());
        } catch (ServiceException e) {
            return text(e.getMessage(), true);
        }
    }

    /**
     * The answer with its files as MCP embedded resources (docs/architecture/mcp.md "Files for the client"): text - code,
     * Markdown, a log - as text, which every client shows and its model can write into a project; anything else as
     * base64, left to the client. A file beyond the size of all files together is named in the text instead.
     */
    static McpSchema.CallToolResult withFiles(String text, List<ChatService.DeliveredFile> files) {
        StringBuilder answer = new StringBuilder(text);
        List<McpSchema.Content> resources = new ArrayList<>();
        for (ChatService.DeliveredFile file : files) {
            if (file.content() == null) {
                answer.append("\n[file \"").append(file.name()).append("\" left out - the files of one answer may have "
                        + "at most 8 MB together]");
            } else {
                resources.add(new McpSchema.EmbeddedResource(null, resource(file)));
            }
        }
        McpSchema.CallToolResult.Builder result = McpSchema.CallToolResult.builder();
        // An answer that only created files has no text of its own - no empty text part before them.
        if (!answer.toString().isBlank() || resources.isEmpty()) {
            result.addTextContent(answer.toString());
        }
        resources.forEach(result::addContent);
        return result.isError(false).build();
    }

    /** A text file of no known type (a returned ".java") goes as text/plain - octet-stream would hide that it is text. */
    private static McpSchema.ResourceContents resource(ChatService.DeliveredFile file) {
        String uri = fileUri(file.name());
        if (file.text() == null) {
            return new McpSchema.BlobResourceContents(uri, file.contentType(),
                    Base64.getEncoder().encodeToString(file.content()));
        }
        String type = file.contentType().equals("application/octet-stream") ? "text/plain" : file.contentType();
        return new McpSchema.TextResourceContents(uri, type, file.text());
    }

    /** "file:///<name>" - the name is all the file has; the client takes the last part of the path as its name. */
    static String fileUri(String name) {
        try {
            return new URI("file", "", "/" + name, null, null).toASCIIString();
        } catch (URISyntaxException e) {
            return "file:///" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }

    /**
     * The files as the client sent them. Their shape the SDK has checked against ASK_SCHEMA before the tool runs (a list
     * of objects with name and content); ChatService checks count, names and sizes.
     */
    private static List<SentFile> sentFiles(@Nullable Object files) {
        List<SentFile> sent = new ArrayList<>();
        if (files instanceof List<?> list) {
            for (Object entry : list) {
                Map<?, ?> file = (Map<?, ?>) entry;
                sent.add(new SentFile((String) file.get("name"), (String) file.get("content")));
            }
        }
        return sent;
    }

    /**
     * What the tenant's client is told about the server when it connects - clients such as Claude Code give it to their
     * model: the assistant goes by the tenant's brand, and the brand is what the people ask for ("Frag Muster AI ...").
     */
    static String instructions(String brand) {
        return brand + " is the AI assistant of the company: it knows the company's own documents, rules and ways of "
                + "working. When the user names " + brand + " - or a part of the name - or asks about the company's own "
                + "knowledge, use this server instead of answering from general knowledge: for facts search the "
                + "documents with " + KnowledgeMcpTools.SEARCH_KNOWLEDGE + ", read on with "
                + KnowledgeMcpTools.READ_DOCUMENT + " and answer yourself, naming the documents; ask " + brand + " with "
                + ASK_ASSISTANT + " for a template, files to check against the company's rules, or an answer that needs "
                + "the company's connected software.";
    }

    /**
     * The key's description and what the assistant offers beyond the documents, each a paragraph before the tenant's
     * instructions (docs/architecture/mcp.md "The brand"): what this access releases comes first, as clients show the
     * start as the server's summary (the general chat's chip shows the description alone). Empty without either.
     */
    static String knows(@Nullable String description, @Nullable String offer) {
        return (description == null ? "" : "This access knows: " + description + "\n\n")
                + (offer == null ? "" : offer + "\n\n");
    }

    /**
     * The tools as the tenant's client sees them: their descriptions name the assistant by the tenant's brand, and
     * those of search_knowledge and ask_assistant end with the key's description (ask_assistant also with what the
     * assistant offers beyond the documents) - a client's model reads the tool
     * descriptions with every question, Claude Code's tool search only those and the instructions.
     */
    static List<McpSchema.Tool> branded(List<McpSchema.Tool> tools, String brand, @Nullable String keyDescription,
                                        @Nullable String offer) {
        String knows = keyDescription == null ? "" : " It knows: " + keyDescription;
        String offers = offer == null ? "" : " " + offer;
        String assistant = brand + " (" + UNBRANDED + ")";
        List<McpSchema.Tool> branded = new ArrayList<>();
        for (McpSchema.Tool tool : tools) {
            String description = switch (tool.name()) {
                case ASK_ASSISTANT -> askDescription(assistant) + knows + offers;
                case LIST_TEMPLATES -> listDescription(assistant);
                case KnowledgeMcpTools.SEARCH_KNOWLEDGE -> KnowledgeMcpTools.searchDescription(brand) + knows;
                case KnowledgeMcpTools.READ_DOCUMENT -> KnowledgeMcpTools.readDescription(brand);
                default -> tool.description();
            };
            branded.add(new McpSchema.Tool(tool.name(), tool.title(), description, tool.inputSchema(),
                    tool.outputSchema(), tool.annotations(), tool.meta(), tool.icons()));
        }
        return branded;
    }

    private static String askDescription(String assistant) {
        return "Asks " + assistant + " a question. It searches the company's documents and answers with the documents "
                + "it used as sources. For facts from the documents prefer " + KnowledgeMcpTools.SEARCH_KNOWLEDGE
                + " and " + KnowledgeMcpTools.READ_DOCUMENT + " and answer yourself; ask here for a template's answer, "
                + "files to check or what needs the company's connected software. Each call stands alone - it does not "
                + "remember earlier calls: give the full context in the question. Text files the question is about (e.g. "
                + "source code to check) go in files, not in the question. Can take up to a minute.";
    }

    private static String listDescription(String assistant) {
        return "Lists the templates of " + assistant + " with what each is for - kinds of questions it answers in a "
                + "way of their own (e.g. a short lookup, a plan, a code review).";
    }

    static Long tenantId(McpTransportContext context) {
        return TenantContext.tenantId(authentication(context));
    }

    /** The MCP key the call was made with - its knowledge areas and web search. */
    static Long mcpKeyId(McpTransportContext context) {
        return TenantContext.mcpKeyId(authentication(context));
    }

    /** /mcp is reached with an MCP key only (intercept-url-map), so the principal is its authentication. */
    private static Authentication authentication(McpTransportContext context) {
        if (!(context instanceof MicronautMcpTransportContext micronaut)
                || !(micronaut.principal() instanceof Authentication authentication)) {
            throw new IllegalStateException("An MCP call without the MCP key's authentication");
        }
        return authentication;
    }

    /** The template of this name (case does not matter); null without one. */
    private Long templateId(Long tenantId, @Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return assistants.templates(tenantId).stream()
                .filter(template -> template.name().equalsIgnoreCase(name.strip()))
                .map(ChatTemplate::id)
                .findFirst()
                .orElseThrow(() -> ServiceException.badRequest("mcp.templateUnknown",
                        "Unknown template \"" + name + "\" - " + templateNames(tenantId), Map.of("name", name)));
    }

    /** Every template's name, with what it is for where a tenant admin wrote it. */
    private String templateList(Long tenantId) {
        List<ChatTemplate> all = assistants.templates(tenantId);
        if (all.isEmpty()) {
            return "The assistant has no templates.";
        }
        StringBuilder text = new StringBuilder("Templates:");
        for (ChatTemplate template : all) {
            text.append("\n- ").append(template.name());
            if (template.description() != null) {
                text.append(": ").append(template.description());
            }
        }
        return text.toString();
    }

    private String templateNames(Long tenantId) {
        List<String> names = assistants.templates(tenantId).stream().map(ChatTemplate::name).toList();
        return names.isEmpty() ? "The assistant has no templates." : "Templates: " + String.join(", ", names);
    }

    private static McpSchema.CallToolResult text(String text, boolean isError) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(isError).build();
    }
}
