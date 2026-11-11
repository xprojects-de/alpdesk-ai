package de.alpdesk.services.tools;

import de.alpdesk.services.document.KnowledgeSearch;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.micronaut.core.annotation.Order;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * Searches the tenant's knowledge base ({@link KnowledgeSearch}) for the chunks closest to a query - the model decides
 * when and with which query (decided 6 October 2026: no automatic search any more). The files found are the answer's
 * sources, and the documents {@link ReadDocumentTool} may read on in.
 */
@Singleton
@Order(1)
public class SimilaritySearchTool implements ChatTool {

    public static final String NAME = "similarity_search";
    private static final int MAX_QUERY = 1000;

    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name(NAME)
            .description("Searches the company's documents (rules, processes, product and price lists, manuals, ...) "
                    + "for the passages closest to the query. Call it before answering any question about the "
                    + "company; for a question with several topics, search once per topic.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("query", "What to look for, in a few words, e.g. \"Garantie Ersatzteile\"")
                    .required("query")
                    .build())
            .build();

    /**
     * Right below the passages: the rule in the system prompt alone did not make Gemini 2.5 Flash read on - it answered
     * "all 12 steps" with the 5 of its passage (October 2026).
     */
    static final String READ_ON_HINT = "These are passages of longer documents. If one is cut off or the question asks "
            + "for more of a document than its passage holds - all steps, all rules, a whole example - call "
            + ReadDocumentTool.NAME + " for that document before you answer; do not answer from a cut-off passage.";

    private final KnowledgeSearch knowledge;
    private final JsonMapper json;

    public SimilaritySearchTool(KnowledgeSearch knowledge, JsonMapper json) {
        this.knowledge = knowledge;
        this.json = json;
    }

    @Override
    public ToolSpecification specification() {
        return SPECIFICATION;
    }

    /** Only with an embedding model: without one the tenant has no knowledge base. */
    @Override
    public boolean availableFor(ToolContext context) {
        return context.tenant().embeddingModelId() != null;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolExecutionRequest request) {
        String query = ToolArguments.of(json, request).requiredString("query").strip();
        if (query.length() > MAX_QUERY) {
            query = query.substring(0, MAX_QUERY);
        }
        List<KnowledgeSearch.Hit> found = knowledge.search(context.tenant(), context.userId(), context.knowledge(), query,
                context.searchResults());
        // Only a search that came back opens the web search: a failed one must not turn the web into the only source.
        context.documentsSearched(true);
        if (found.isEmpty()) {
            return ToolResult.text("No passage of the company's documents matches \"" + query + "\".");
        }
        StringBuilder result = new StringBuilder();
        boolean partsOfLongerDocuments = false;
        for (KnowledgeSearch.Hit hit : found) {
            context.sources().add(hit.fileName());
            String name = context.documentFound(hit.fileName(), hit.documentId(), hit.text());
            result.append("--- From \"").append(name).append("\"");
            if (hit.documentChunks() > 1) {
                result.append(" (one passage of ").append(hit.documentChunks()).append(")");
                partsOfLongerDocuments = true;
            }
            result.append(" ---\n").append(hit.text()).append("\n\n");
        }
        // Only for a model that calls tools: one the application searches for cannot read on.
        if (partsOfLongerDocuments && context.model().callsTools()) {
            result.append(READ_ON_HINT);
        }
        return ToolResult.text(result.toString().strip());
    }
}
