package de.alpdesk.services.tools;

import de.alpdesk.services.chat.ChatFileService;
import de.alpdesk.services.chat.OutputFormat;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.micronaut.core.annotation.Order;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.util.Arrays;
import java.util.Locale;

/**
 * Creates a file the user downloads ("adapt this document and give it back to me"). The content is stored at once;
 * the file is attached to the answer when the answer is stored (services.chat.ChatService).
 */
@Singleton
@Order(3)
public class CreateFileTool implements ChatTool {

    public static final String NAME = "create_file";
    /**
     * Files one answer may create - the ones a connected software's tools return count too, images not (a sequence of
     * stills has its own limit, HandOutFileTool.MAX_IMAGES_PER_ANSWER) -, and the characters of each.
     */
    public static final int MAX_FILES_PER_ANSWER = 5;
    static final int MAX_CONTENT = 2_000_000;

    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name(NAME)
            .description("Creates a file the user can download, e.g. an adapted version of an attached file. Use it "
                    + "whenever the user asks for a file instead of pasting the whole content into the answer.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("file_name", "The file name, e.g. wartungsplan-2027.docx")
                    .addEnumProperty("format", Arrays.stream(OutputFormat.values()).map(Enum::name).toList(),
                            "DOCX and PDF: write the content as Markdown (headings, lists, tables). XLSX: write it as "
                                    + "CSV separated by semicolons. Otherwise the file's own text.")
                    .addStringProperty("content", "The complete content of the file")
                    .required("file_name", "format", "content")
                    .build())
            .build();

    private final ChatFileService files;
    private final JsonMapper json;

    public CreateFileTool(ChatFileService files, JsonMapper json) {
        this.files = files;
        this.json = json;
    }

    /** Only where a created file goes somewhere: kept in the conversation, or sent with the answer. */
    @Override
    public boolean availableFor(ToolContext context) {
        return context.takesFiles();
    }

    @Override
    public ToolSpecification specification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolExecutionRequest request) {
        if (context.createdFilesExceptImages() >= MAX_FILES_PER_ANSWER) {
            return ToolResult.error("at most " + MAX_FILES_PER_ANSWER + " files per answer");
        }
        ToolArguments arguments = ToolArguments.of(json, request);
        String content = arguments.requiredString("content");
        OutputFormat format = format(arguments.requiredString("format"));
        if (format == null) {
            return ToolResult.error("format must be one of " + Arrays.toString(OutputFormat.values()));
        }
        if (content.length() > MAX_CONTENT) {
            return ToolResult.error("the content may have at most " + MAX_CONTENT + " characters");
        }
        ChatFileService.PendingFile file = files.createGenerated(context.tenant(),
                arguments.optionalString("file_name"), format, content);
        context.createdFiles().add(file);
        return ToolResult.text(context.fileNote(file.fileName()));
    }

    private static OutputFormat format(String name) {
        try {
            return OutputFormat.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
