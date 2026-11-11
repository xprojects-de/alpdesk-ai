package de.alpdesk.services.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Order;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Searches a large file sent with the question (a log, a data export) for text and returns the matching lines with
 * their numbers - the model decides what it looks for, and reads the lines around a match with read_file_lines
 * (docs/architecture/ai-features.md "Files sent with a question"). Plain text, case-insensitive; "|" separates
 * alternatives. No regular expressions: one written by a model could take a 4 MB file for minutes.
 */
@Singleton
@Order(4)
public class SearchFileTool implements ChatTool {

    public static final String NAME = "search_file";
    /** One matching line is cut after this many characters - a log line can be a whole JSON document. */
    static final int MAX_LINE = 1000;

    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name(NAME)
            .description("Searches a large file sent with the question (see its overview with the question) and returns "
                    + "the matching lines with their line numbers. Case-insensitive plain text; separate alternatives "
                    + "with | (e.g. \"ERROR|FATAL\"). Then read the lines around a match with read_file_lines.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("file_name", "The file's name, as in its overview")
                    .addStringProperty("query", "The text to find - alternatives separated by |")
                    .addIntegerProperty("from_line", "Optional: search from this line on (1-based), e.g. for the next matches")
                    .required("file_name", "query")
                    .build())
            .build();

    private final JsonMapper json;
    private final int maxResults;
    private final int maxText;

    public SearchFileTool(JsonMapper json, @Value("${app.chat.sent-files.search-results}") int maxResults,
                          @Value("${app.chat.sent-files.tool-text}") int maxText) {
        this.json = json;
        this.maxResults = maxResults;
        this.maxText = maxText;
    }

    @Override
    public ToolSpecification specification() {
        return SPECIFICATION;
    }

    @Override
    public boolean availableFor(ToolContext context) {
        return !context.exploredFiles().isEmpty();
    }

    @Override
    public ToolResult execute(ToolContext context, ToolExecutionRequest request) {
        ToolArguments arguments = ToolArguments.of(json, request);
        ExploredFile file = ExploredFiles.find(context, arguments.requiredString("file_name"));
        List<String> terms = terms(arguments.requiredString("query"));
        Integer from = arguments.optionalInt("from_line");
        int start = from == null ? 0 : Math.max(0, from - 1);

        StringBuilder matches = new StringBuilder();
        int found = 0;
        int shown = 0;
        int lastShown = 0;
        // Full at the first match that does not fit: a shorter one after it would hide it from from_line.
        boolean full = false;
        for (int i = start; i < file.lines().size(); i++) {
            String line = file.lines().get(i);
            if (!matches(line, terms)) {
                continue;
            }
            found++;
            String entry = (i + 1) + ": " + ExploredFiles.cut(line, MAX_LINE) + "\n";
            full = full || shown == maxResults || (shown > 0 && matches.length() + entry.length() > maxText);
            if (!full) {
                matches.append(entry);
                shown++;
                lastShown = i + 1;
            }
        }
        return ToolResult.text(summary(file, found, shown, lastShown) + matches);
    }

    private static String summary(ExploredFile file, int found, int shown, int lastShown) {
        if (found == 0) {
            return "No line of \"" + file.name() + "\" matches.";
        }
        String counted = found == 1 ? "1 matching line" : found + " matching lines";
        if (shown == found) {
            return counted + " in \"" + file.name() + "\":\n";
        }
        return counted + " in \"" + file.name() + "\", the first " + shown + " shown - search more "
                + "narrowly, or go on with from_line " + (lastShown + 1) + ":\n";
    }

    private static List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        for (String term : query.split("\\|")) {
            if (!term.isBlank()) {
                terms.add(term.strip().toLowerCase(Locale.ROOT));
            }
        }
        return terms;
    }

    private static boolean matches(String line, List<String> terms) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (lower.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
