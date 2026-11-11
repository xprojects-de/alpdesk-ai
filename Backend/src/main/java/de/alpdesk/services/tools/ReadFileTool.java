package de.alpdesk.services.tools;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.chat.ConversationFile;
import de.alpdesk.services.chat.ChatFileReader;
import de.alpdesk.services.chat.ChatFileService;
import de.alpdesk.services.document.DocumentImages;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.micronaut.core.annotation.Order;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a file of the conversation for the model. The question only lists the files; their content reaches the model
 * through this tool, when it needs it. Without a mode the application picks the form (decided 6 October 2026): what the
 * answering model reads as it is goes as the original - an image always, a PDF (up to 20 MB), audio (the small copy
 * made on upload) when the catalog says the model reads them; everything else as text. A model that cannot read the
 * original gets the fallback: a scan's page images or a PDF's text, an audio file's transcript. A video goes to no
 * model as it is: the model examines it with code (docs/architecture/code-execution.md "Videos"). The model may also ask for TEXT (e.g. a long text PDF, much cheaper than the original),
 * PAGES (a PDF's pages as images) or ORIGINAL itself.
 */
@Singleton
@Order(2)
public class ReadFileTool implements ChatTool {

    public static final String NAME = "read_file";
    /** Characters of text one call returns - a longer file is read in parts (offset). */
    static final int TEXT_PART = 100_000;
    /** Pages one call returns as images. */
    static final int MAX_PAGES = 10;
    /** The largest PDF sent as itself - the providers take about 20 to 32 MB per request. */
    static final int MAX_ORIGINAL_BYTES = 20 * 1024 * 1024;

    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name(NAME)
            .description("Reads a file of this conversation (see the list of files with the question). Give just the "
                    + "file_id and the application picks the best form you can read: PDFs, images and audio as they "
                    + "are where you can read them, a video only through code, other files as text. Ask for mode TEXT to read a long text "
                    + "document as text only (much cheaper), PAGES for a PDF's pages as images.")
            .parameters(JsonObjectSchema.builder()
                    .addIntegerProperty("file_id", "The file_id from the list of files")
                    .addEnumProperty("mode", List.of("TEXT", "PAGES", "ORIGINAL"),
                            "Optional - leave it out to get the best form")
                    .addIntegerProperty("offset", "TEXT: the character to start at, for the next part of a long file")
                    .addIntegerProperty("first_page", "PAGES: the first page (1-based), default 1")
                    .addIntegerProperty("last_page", "PAGES: the last page, at most " + MAX_PAGES + " pages per call")
                    .required("file_id")
                    .build())
            .build();

    private final ChatFileService files;
    private final ChatFileReader reader;
    private final CodeSessions codeSessions;
    private final JsonMapper json;

    ReadFileTool(ChatFileService files, ChatFileReader reader, CodeSessions codeSessions, JsonMapper json) {
        this.files = files;
        this.reader = reader;
        this.codeSessions = codeSessions;
        this.json = json;
    }

    @Override
    public ToolSpecification specification() {
        return SPECIFICATION;
    }

    @Override
    public boolean availableFor(ToolContext context) {
        return context.conversationHasFiles();
    }

    @Override
    public ToolResult execute(ToolContext context, ToolExecutionRequest request) {
        ToolArguments arguments = ToolArguments.of(json, request);
        ConversationFile file = files.findInConversation(context.dataSource(), context.conversationId(),
                arguments.requiredLong("file_id")).orElse(null);
        if (file == null) {
            return ToolResult.error("there is no such file in this conversation");
        }
        if (file.isExpired()) {
            return ToolResult.error("\"" + file.fileName() + "\" " + file.expiredNote());
        }
        if (file.contentType().startsWith("image/")) {
            return images(file, files.storedImages(file), 1, "The image \"" + file.fileName() + "\" follows the tool results.");
        }
        String mode = arguments.optionalString("mode");
        return switch (mode == null ? "AUTO" : mode.trim().toUpperCase(Locale.ROOT)) {
            case "AUTO", "ORIGINAL" -> best(context, file);
            case "TEXT" -> text(context, file, arguments.optionalInt("offset"));
            case "PAGES" -> pages(file, arguments.optionalInt("first_page"), arguments.optionalInt("last_page"));
            default -> ToolResult.error("mode must be TEXT, PAGES or ORIGINAL - or left out");
        };
    }

    /** The original where the model reads it, else the best fallback. */
    private ToolResult best(ToolContext context, ConversationFile file) {
        AiModel model = context.model();
        if (file.isAudio()) {
            return model.readsAudio() ? media(file) : transcript(context, file);
        }
        if (file.isVideo()) {
            return video(context, file);
        }
        if (file.contentType().equals("application/pdf")) {
            if (model.readsPdf() && file.sizeBytes() <= MAX_ORIGINAL_BYTES) {
                return new ToolResult("The original PDF \"" + file.fileName() + "\" follows the tool results.",
                        List.of(new ToolResult.ToolAttachment("\"" + file.fileName() + "\"", "application/pdf", files.content(file))));
            }
            return file.imageCount() > 0 ? pages(file, 1, MAX_PAGES) : text(context, file, 0);
        }
        return text(context, file, 0);
    }

    /**
     * A video is looked at through code: frames cut with ffmpeg in the sandbox, seen with view_images - only the video
     * image of the sandbox has what it takes.
     */
    private ToolResult video(ToolContext context, ConversationFile file) {
        String name = "\"" + file.fileName() + "\"";
        if (!codeSessions.available(context)) {
            return ToolResult.error(name + " is a video, and the code execution it is examined with is not available "
                    + "right now - tell the user");
        }
        if (!codeSessions.examinesVideo(context)) {
            return ToolResult.error(name + " is a video, and this company's code execution has no video tools (it is "
                    + "not the video sandbox) - tell the user an admin can give it one");
        }
        return ToolResult.error(name + " is a video - no model reads it as it is. Examine it with code: it is in data/ "
                + "(run_shell: ffprobe for its length, ffmpeg to cut frames or clips), then look at the frames with "
                + "view_images");
    }

    private ToolResult media(ConversationFile file) {
        return new ToolResult("The audio \"" + file.fileName() + "\" follows the tool results.",
                List.of(new ToolResult.ToolAttachment("\"" + file.fileName() + "\"", file.mediaType(), files.media(file))));
    }

    private ToolResult transcript(ToolContext context, ConversationFile file) {
        String text = files.text(context.dataSource(), file);
        if (text == null) {
            return ToolResult.error(context.model().displayName() + " cannot listen to audio and \"" + file.fileName()
                    + "\" has no transcript - tell the user to choose a model that can, e.g. Gemini");
        }
        return ToolResult.text("Transcript of \"" + file.fileName() + "\" (you cannot listen to the audio itself):\n" + text);
    }

    private ToolResult text(ToolContext context, ConversationFile file, Integer offset) {
        if (file.isVideo()) {
            return ToolResult.error("a video has no text - leave the mode out");
        }
        String text = files.text(context.dataSource(), file);
        if (text == null) {
            // A PDF scan is read as pages; an archive a connected software returned only code or the user opens.
            return ToolResult.error(file.contentType().equals("application/pdf")
                    ? "\"" + file.fileName() + "\" has no text - leave the mode out"
                    : noText(context, file));
        }
        int start = offset == null ? 0 : Math.max(0, offset);
        if (start >= text.length()) {
            return ToolResult.text("\"" + file.fileName() + "\" has only " + text.length() + " characters.");
        }
        int end = Math.min(text.length(), start + TEXT_PART);
        StringBuilder result = new StringBuilder("=== File \"").append(file.fileName()).append("\" ===\n")
                .append(text, start, end);
        if (end < text.length()) {
            result.append("\n[Characters ").append(start).append(" to ").append(end).append(" of ").append(text.length())
                    .append(" - read on with offset ").append(end).append("]");
        }
        return ToolResult.text(result.toString());
    }

    /**
     * A file without text (an archive): with the tenant's sandbox the model's code opens it - every file of the
     * conversation is in its data/ -, otherwise only the user, by downloading it. Without the hint the model stops at
     * "no text" although it could unpack the archive.
     */
    private String noText(ToolContext context, ConversationFile file) {
        String name = "\"" + file.fileName() + "\"";
        return codeSessions.available(context)
                ? name + " has no text you can read - open it with run_shell or run_python: it is in data/ (an archive: "
                        + "unpack it there)"
                : name + " has no text you can read - it is for the user to download";
    }

    private ToolResult pages(ConversationFile file, Integer firstPage, Integer lastPage) {
        if (!file.contentType().equals("application/pdf")) {
            return ToolResult.error("mode PAGES is for PDFs - leave the mode out for \"" + file.fileName() + "\"");
        }
        int first = firstPage == null ? 1 : Math.max(1, firstPage);
        int last = Math.min(lastPage == null ? first + MAX_PAGES - 1 : lastPage, first + MAX_PAGES - 1);
        if (last < first) {
            return ToolResult.error("last_page is before first_page");
        }
        if (file.imageCount() > 0) {
            // A scan: its pages were stored as images on upload.
            List<byte[]> stored = files.storedImages(file);
            List<byte[]> selected = new ArrayList<>(stored.subList(Math.min(first - 1, stored.size()), Math.min(last, stored.size())));
            return images(file, selected, first, pagesText(file, first, selected.size(), stored.size()));
        }
        DocumentImages.RenderedPages rendered = reader.renderPages(files.content(file), first, last);
        return images(file, rendered.images(), first, pagesText(file, first, rendered.images().size(), rendered.pageCount()));
    }

    private static String pagesText(ConversationFile file, int first, int count, int pageCount) {
        if (count == 0) {
            return "\"" + file.fileName() + "\" has only " + pageCount + " pages.";
        }
        return "Pages " + first + " to " + (first + count - 1) + " of " + pageCount + " of \"" + file.fileName()
                + "\" follow the tool results as images.";
    }

    private static ToolResult images(ConversationFile file, List<byte[]> images, int firstNumber, String text) {
        List<ToolResult.ToolAttachment> labelled = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            String label = images.size() == 1 && file.contentType().startsWith("image/") ? "\"" + file.fileName() + "\""
                    : "Page " + (firstNumber + i) + " of \"" + file.fileName() + "\"";
            labelled.add(ToolResult.ToolAttachment.jpeg(label, images.get(i)));
        }
        return new ToolResult(text, labelled);
    }
}
