package de.alpdesk.services.tools;

import de.alpdesk.services.common.ServiceException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Order;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.util.Map;

/**
 * Reads a range of lines of a large file sent with the question - the context around what search_file found, or the
 * file from its start (docs/architecture/ai-features.md "Files sent with a question").
 */
@Singleton
@Order(5)
public class ReadFileLinesTool implements ChatTool {

    public static final String NAME = "read_file_lines";
    /** One line is cut after this many characters. */
    static final int MAX_LINE = 2000;

    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name(NAME)
            .description("Reads lines of a large file sent with the question, with their line numbers - e.g. the lines "
                    + "before and after a match of search_file, to understand what led to it.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("file_name", "The file's name, as in its overview")
                    .addIntegerProperty("from_line", "The first line (1-based)")
                    .addIntegerProperty("to_line", "The last line")
                    .required("file_name", "from_line", "to_line")
                    .build())
            .build();

    private final JsonMapper json;
    private final int maxLines;
    private final int maxText;

    public ReadFileLinesTool(JsonMapper json, @Value("${app.chat.sent-files.read-lines}") int maxLines,
                             @Value("${app.chat.sent-files.tool-text}") int maxText) {
        this.json = json;
        this.maxLines = maxLines;
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
        int from = (int) arguments.requiredLong("from_line");
        int to = (int) arguments.requiredLong("to_line");
        int lines = file.lines().size();
        if (from < 1 || from > lines || to < from) {
            throw ServiceException.badRequest("tool.linesOutOfRange", "\"" + file.name() + "\" has lines 1 to " + lines
                    + " - from_line must lie in it and to_line must not be before it",
                    Map.of("file", file.name(), "lines", String.valueOf(lines)));
        }
        int last = Math.min(Math.min(to, lines), from + maxLines - 1);
        StringBuilder text = new StringBuilder();
        int shownTo = from - 1;
        for (int i = from; i <= last; i++) {
            String entry = i + ": " + ExploredFiles.cut(file.lines().get(i - 1), MAX_LINE) + "\n";
            // The first line always comes, however long - else a call could never get past it.
            if (!text.isEmpty() && text.length() + entry.length() > maxText) {
                break;
            }
            text.append(entry);
            shownTo = i;
        }
        String head = "Lines " + from + " to " + shownTo + " of " + lines + " in \"" + file.name() + "\""
                + (shownTo < Math.min(to, lines) ? " (at most " + maxLines + " lines and " + maxText
                + " characters per call - read on from line " + (shownTo + 1) + ")" : "") + ":\n";
        return ToolResult.text(head + text);
    }
}
