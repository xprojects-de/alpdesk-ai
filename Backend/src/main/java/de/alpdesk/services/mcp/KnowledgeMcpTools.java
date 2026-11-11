package de.alpdesk.services.mcp;

import de.alpdesk.entities.document.Document;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.document.DocumentRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.KnowledgeAreaService;
import de.alpdesk.services.document.KnowledgeScope;
import de.alpdesk.services.document.KnowledgeSearch;
import de.alpdesk.services.tenant.TenantDatabases;
import de.alpdesk.services.tools.ReadDocumentTool;
import io.micronaut.context.annotation.Factory;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The tenant's MCP server's own reading of the company knowledge (docs/architecture/mcp.md "Searching the knowledge",
 * roadmap point 37): the client's model - Claude in Cowork, claude.ai or Claude Code - searches and reads on itself and
 * answers from what it read, instead of a second model answering through ask_assistant. It finds what ask_assistant
 * finds: the company knowledge and the areas of the MCP key the call was made with.
 * <p>
 * /mcp keeps nothing between calls, so read_document cannot rely on "found earlier in this answer" like the chat's: the
 * search hands out each passage's document id and where it starts, and every read checks again that the MCP server
 * finds the document - a deleted one, one moved to another area or one being embedded again is not found.
 */
@Factory
public class KnowledgeMcpTools {

    static final String SEARCH_KNOWLEDGE = "search_knowledge";
    static final String READ_DOCUMENT = "read_document";
    /** How the descriptions name the knowledge before the tenant is known - BrandedMcpServer puts in its brand. */
    private static final String UNBRANDED = "the company";
    /** The longest query searched - a search query, not a document. */
    private static final int MAX_QUERY = 1_000;
    private static final McpSchema.ToolAnnotations READ_ONLY = new McpSchema.ToolAnnotations(null, true, false, null, null, null);
    private static final String SEARCH_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {"type": "string", "description": "What to look for, in the words the documents would use - a question or its key words"}
              },
              "required": ["query"]
            }""";
    private static final String READ_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "document": {"type": "integer", "description": "The document's id as search_knowledge gave it"},
                "offset": {"type": "integer", "description": "Optional - the character to start at, as search_knowledge or an earlier result of this tool names it"}
              },
              "required": ["document"]
            }""";

    private final KnowledgeSearch knowledge;
    private final KnowledgeAreaService areas;
    private final AssistantService assistants;
    private final TenantRepository tenants;
    private final TenantDatabases databases;
    private final DocumentRepository documents;
    private final McpJsonMapper json;

    public KnowledgeMcpTools(KnowledgeSearch knowledge, KnowledgeAreaService areas, AssistantService assistants,
                             TenantRepository tenants, TenantDatabases databases, DocumentRepository documents,
                             McpJsonMapper json) {
        this.knowledge = knowledge;
        this.areas = areas;
        this.assistants = assistants;
        this.tenants = tenants;
        this.databases = databases;
        this.documents = documents;
        this.json = json;
    }

    @Singleton
    SyncToolSpecification searchKnowledge() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(SEARCH_KNOWLEDGE)
                .description(searchDescription(UNBRANDED))
                .inputSchema(json, SEARCH_SCHEMA)
                .annotations(READ_ONLY)
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler(AssistantMcpTools.guarded(SEARCH_KNOWLEDGE, this::search)).build();
    }

    @Singleton
    SyncToolSpecification readDocument() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(READ_DOCUMENT)
                .description(readDescription(UNBRANDED))
                .inputSchema(json, READ_SCHEMA)
                .annotations(READ_ONLY)
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler(AssistantMcpTools.guarded(READ_DOCUMENT, this::read)).build();
    }

    /**
     * The passages that answer the query - as many as the tenant's chat finds (its assistant settings), each under its
     * document's name, id and, for a longer document, where it starts. Embedding the query is recorded as the tenant's
     * usage, like ask_assistant's. A missing key or a failing provider is a tool error the client's model reads.
     */
    private McpSchema.CallToolResult search(McpTransportContext context, McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        if (!(arguments.get("query") instanceof String query) || query.isBlank()) {
            return text("The query is required.", true);
        }
        Tenant tenant = tenant(AssistantMcpTools.tenantId(context));
        KnowledgeScope scope = areas.scopeOfMcpKey(AssistantMcpTools.mcpKeyId(context));
        if (tenant.embeddingModelId() == null) {
            return text("The company has no searchable documents yet - no embedding model is chosen.", true);
        }
        String searched = query.strip().length() > MAX_QUERY ? query.strip().substring(0, MAX_QUERY) : query.strip();
        try {
            int count = assistants.forQuestion(tenant.id(), null).searchResults();
            List<KnowledgeSearch.Hit> hits = knowledge.search(tenant, null, scope, searched, count);
            return text(passages(tenant, scope, searched, hits), false);
        } catch (ServiceException e) {
            return text(e.getMessage(), true);
        }
    }

    private String passages(Tenant tenant, KnowledgeScope scope, String query, List<KnowledgeSearch.Hit> hits) {
        if (hits.isEmpty()) {
            return "No passage of the company's documents matches \"" + query + "\".";
        }
        StringBuilder result = new StringBuilder();
        Map<Long, String> texts = new HashMap<>();
        boolean partsOfLongerDocuments = false;
        for (KnowledgeSearch.Hit hit : hits) {
            result.append("--- From \"").append(hit.fileName()).append("\" (document ").append(hit.documentId());
            if (hit.documentChunks() > 1) {
                String text = texts.computeIfAbsent(hit.documentId(), id -> foundText(tenant, scope, id));
                result.append(", one passage of ").append(hit.documentChunks());
                if (text.length() > ReadDocumentTool.WHOLE_DOCUMENT) {
                    int start = Math.max(0, ReadDocumentTool.locate(text, hit.text()) - ReadDocumentTool.BEFORE_PASSAGE);
                    result.append(", read on from offset ").append(start).append(" of ").append(text.length());
                }
                partsOfLongerDocuments = true;
            }
            result.append(") ---\n").append(hit.text()).append("\n\n");
        }
        if (partsOfLongerDocuments) {
            result.append("These are passages of longer documents. If one is cut off or the question needs more of a "
                    + "document than its passage holds - all steps, all rules, a whole example - call " + READ_DOCUMENT
                    + " with the document's id (and the offset named) before you answer.");
        }
        return result.toString().strip();
    }

    /**
     * A part of a document the MCP server finds: a short one whole, a longer one about a page and a half from the offset
     * (from its start without one). Unknown, deleted, moved out of the MCP server's areas or waiting to be embedded
     * again - all the same "not found": the server tells no document it does not find from one that does not exist.
     */
    private McpSchema.CallToolResult read(McpTransportContext context, McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        if (!(arguments.get("document") instanceof Number document)) {
            return text("The document's id is required - as search_knowledge gave it.", true);
        }
        Integer offset = arguments.get("offset") instanceof Number number ? number.intValue() : null;
        if (offset != null && offset < 0) {
            return text("The offset must not be negative.", true);
        }
        Tenant tenant = tenant(AssistantMcpTools.tenantId(context));
        KnowledgeScope scope = areas.scopeOfMcpKey(AssistantMcpTools.mcpKeyId(context));
        Long id = document.longValue();
        DataSource dataSource = databases.dataSource(tenant.databaseName());
        String text = documents.findFoundText(dataSource, id, allowedAreas(scope)).orElse(null);
        String name = text == null ? null : documents.findById(dataSource, id).map(Document::path).orElse(null);
        if (name == null) {
            return text("Document " + id + " is not found - it may have been deleted, moved or be updating: search "
                    + "again.", true);
        }
        if (offset != null && offset >= text.length()) {
            return text("\"" + name + "\" (document " + id + ") has only " + text.length() + " characters.", false);
        }
        int start = text.length() <= ReadDocumentTool.WHOLE_DOCUMENT || offset == null ? 0 : offset;
        int end = text.length() <= ReadDocumentTool.WHOLE_DOCUMENT ? text.length()
                : Math.min(text.length(), start + ReadDocumentTool.PART);
        return text(ReadDocumentTool.part(name, text, start, end, false), false);
    }

    private String foundText(Tenant tenant, KnowledgeScope scope, Long documentId) {
        return documents.findFoundText(databases.dataSource(tenant.databaseName()), documentId, allowedAreas(scope))
                .orElse("");
    }

    /** The areas a read may reach besides the company knowledge; null: every area. */
    private static List<Long> allowedAreas(KnowledgeScope scope) {
        return scope.findsEverything() ? null : scope.areaIdList();
    }

    /** The MCP key's tenant - the key validator lets only an active tenant's key through. */
    private Tenant tenant(Long tenantId) {
        return tenants.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("The MCP key's tenant " + tenantId + " is gone"));
    }

    static String searchDescription(String company) {
        return "Searches the documents of " + company + " - its own knowledge: rules, how-tos, projects, manuals - and "
                + "returns the passages that answer the query best, each under its document's name and id. Use it for "
                + "facts from the company's documents and answer yourself from the passages, naming the documents. A "
                + "passage of a longer document may be cut off: read on with " + READ_DOCUMENT + ". Nothing fits: "
                + "search again in other words.";
    }

    static String readDescription(String company) {
        return "Reads on in a document of " + company + " that " + SEARCH_KNOWLEDGE + " found, by its id: a short "
                + "document whole, a long one in parts of about a page and a half - from the offset " + SEARCH_KNOWLEDGE
                + " or an earlier part names. Use it when a passage is cut off or the answer needs what comes before or "
                + "after it: the rest of a list or of the steps, the example to a rule.";
    }

    private static McpSchema.CallToolResult text(String text, boolean isError) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(isError).build();
    }
}
