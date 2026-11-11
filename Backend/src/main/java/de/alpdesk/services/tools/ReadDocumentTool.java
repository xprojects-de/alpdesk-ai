package de.alpdesk.services.tools;

import de.alpdesk.repositories.document.DocumentRepository;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.micronaut.core.annotation.Order;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads on in a document {@link SimilaritySearchTool} found in this answer (docs/architecture/ai-features.md "Reading
 * on in a document"): a passage of 1000 characters may give the rule but not its example, or step 4 of 12. A short
 * document comes whole, a long one around the passage found, and further parts from an offset - the model decides
 * whether and how far it reads, within a limit per answer. Only documents the search found: the asker's areas hold by
 * construction, and are checked again when read.
 */
@Singleton
@Order(6)
public class ReadDocumentTool implements ChatTool {

    public static final String NAME = "read_document";
    /** A document up to this length is read whole - a how-to, a short guide (some three to four pages). */
    public static final int WHOLE_DOCUMENT = 12_000;
    /** Characters of a longer document one call returns - about a page and a half. */
    public static final int PART = 5_000;
    /** Characters before the passage found where the first part starts: the passage with about two of its size around it. */
    public static final int BEFORE_PASSAGE = 2_000;
    /** Characters all calls of one answer read together - a question never reads a whole manual. */
    static final int ANSWER_LIMIT = 30_000;

    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name(NAME)
            .description("Reads on in a document similarity_search found: a short document whole, a long one around "
                    + "the passage found (about a page and a half). Use it when a passage is cut off or the answer needs "
                    + "what comes before or after it - the rest of a list or of the steps, the example to a rule. For "
                    + "more of a long document, call it again with the offset its result names.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("document", "The document's name exactly as similarity_search gave it after \"From\"")
                    .addIntegerProperty("offset", "Optional - the character to start at, as an earlier result of this tool names it")
                    .required("document")
                    .build())
            .build();

    private final DocumentRepository documents;
    private final JsonMapper json;

    public ReadDocumentTool(DocumentRepository documents, JsonMapper json) {
        this.documents = documents;
        this.json = json;
    }

    @Override
    public ToolSpecification specification() {
        return SPECIFICATION;
    }

    /** With the search only: it reads what the search found. */
    @Override
    public boolean availableFor(ToolContext context) {
        return context.tenant().embeddingModelId() != null;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolExecutionRequest request) {
        ToolArguments arguments = ToolArguments.of(json, request);
        String name = arguments.requiredString("document").strip();
        ToolContext.FoundDocument found = context.foundDocument(name);
        if (found == null) {
            return ToolResult.error("read_document reads only documents similarity_search found in this answer - "
                    + "search first, then give the name it shows after \"From\"");
        }
        int remaining = ANSWER_LIMIT - context.documentCharactersRead();
        if (remaining <= 0) {
            return ToolResult.error("the reading limit of this answer is reached - answer with what you have read");
        }
        List<Long> allowedAreas = context.knowledge().findsEverything() ? null : context.knowledge().areaIdList();
        String text = documents.findFoundText(context.dataSource(), found.documentId(), allowedAreas).orElse(null);
        if (text == null) {
            return ToolResult.error("\"" + name + "\" can no longer be read - it was deleted, moved or is being updated");
        }
        Integer offset = arguments.optionalInt("offset");
        if (offset != null && offset >= text.length()) {
            return ToolResult.text("\"" + name + "\" has only " + text.length() + " characters.");
        }
        int start = text.length() <= WHOLE_DOCUMENT ? 0
                : offset != null ? Math.max(0, offset) : Math.max(0, locate(text, found.passage()) - BEFORE_PASSAGE);
        int wanted = text.length() <= WHOLE_DOCUMENT ? text.length() : Math.min(text.length(), start + PART);
        int end = Math.min(wanted, start + remaining);
        context.documentRead(end - start);
        context.sources().add(name);
        return ToolResult.text(part(name, text, start, end, end < wanted));
    }

    /** The part from start to end under a heading with where it stands, and where to read on - also the MCP server's. */
    public static String part(String name, String text, int start, int end, boolean cutByLimit) {
        if (start == 0 && end == text.length()) {
            return "=== Document \"" + name + "\" (complete) ===\n" + text;
        }
        StringBuilder result = new StringBuilder("=== Document \"").append(name).append("\", characters ").append(start)
                .append(" to ").append(end).append(" of ").append(text.length()).append(" ===\n")
                .append(text, start, end);
        if (cutByLimit) {
            return result.append("\n[The reading limit of this answer is reached - answer with what you have read]").toString();
        }
        StringJoiner further = new StringJoiner("; ", "\n[", "]");
        if (start > 0) {
            further.add("earlier text: offset " + Math.max(0, start - PART));
        }
        if (end < text.length()) {
            further.add("read on with offset " + end);
        }
        return result.append(further).toString();
    }

    /**
     * Where the passage found starts in its document's text; 0 if it cannot be told. A chunk is the document's context
     * header and a piece of its text, but the splitter joins paragraphs and sentences with whitespace of its own - so
     * its words are looked for with any whitespace between them. All of them: a chunk starts with the overlap of the one
     * before, and its first words may stand in many places of the document (a recurring sentence).
     */
    public static int locate(String text, String passage) {
        int header = passage.indexOf("\n\n");
        String body = (header < 0 ? passage : passage.substring(header + 2)).strip();
        if (body.isEmpty()) {
            return 0;
        }
        StringJoiner pattern = new StringJoiner("\\s+");
        for (String word : body.split("\\s+")) {
            pattern.add(Pattern.quote(word));
        }
        Matcher matcher = Pattern.compile(pattern.toString()).matcher(text);
        return matcher.find() ? matcher.start() : 0;
    }
}
