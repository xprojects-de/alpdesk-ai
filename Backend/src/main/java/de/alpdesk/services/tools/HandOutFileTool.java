package de.alpdesk.services.tools;

import de.alpdesk.services.chat.ChatFileService;
import de.alpdesk.services.sandbox.SandboxSession;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.micronaut.core.annotation.Order;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

/**
 * Gives the user files the model's code wrote in the sandbox (docs/architecture/code-execution.md "Videos"): stills
 * with marks drawn in, a clip cut out of a video, a table - stored like a file of create_file, so they outlive the
 * session, which deletes everything with the answer. Several in one call: a movement's stills, which the chat shows as
 * one sequence with a slider - one call each would use up the answer's rounds.
 */
@Singleton
@Order(11)
public class HandOutFileTool implements ChatTool {

    public static final String NAME = "hand_out_file";
    /** Images one answer may hand out - the stills of a movement; other files count against create_file's limit. */
    public static final int MAX_IMAGES_PER_ANSWER = 30;

    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name(NAME)
            .description("Gives the user files your code wrote with run_python or run_shell - images with marks drawn "
                    + "in, a video clip (MP4), a CSV or document. Several at once in the order the user should see "
                    + "them: two or more images are shown as one sequence with a slider, like a short clip of stills. "
                    + "The session's files are gone after your answer; only a file handed out stays. At most "
                    + MAX_IMAGES_PER_ANSWER + " images and " + CreateFileTool.MAX_FILES_PER_ANSWER + " other files "
                    + "per answer, " + ChatFileService.MAX_FILE_BYTES / (1024 * 1024) + " MB each.")
            .parameters(JsonObjectSchema.builder()
                    .addProperty("paths", JsonArraySchema.builder()
                            .description("The files' paths relative to the directory your code runs in, in order, "
                                    + "e.g. out/still_01.png, out/still_02.png")
                            .items(new JsonStringSchema())
                            .build())
                    .addStringProperty("file_name", "Optional, for a single file: the name the user sees, e.g. "
                            + "marked-still.png - else the file's own")
                    .required("paths")
                    .build())
            .build();

    private final CodeSessions sessions;
    private final ChatFileService files;
    private final JsonMapper json;

    HandOutFileTool(CodeSessions sessions, ChatFileService files, JsonMapper json) {
        this.sessions = sessions;
        this.files = files;
        this.json = json;
    }

    @Override
    public ToolSpecification specification() {
        return SPECIFICATION;
    }

    /** Only where a file goes somewhere - kept in the conversation, or sent with the answer. */
    @Override
    public boolean availableFor(ToolContext context) {
        return context.takesFiles() && sessions.available(context);
    }

    /** Waits for the sandbox - no transaction is held; the files' rows are stored with the answer. */
    @Override
    public ToolResult execute(ToolContext context, ToolExecutionRequest request) {
        ToolArguments arguments = ToolArguments.of(json, request);
        List<String> paths = arguments.requiredDistinctStrings("paths");
        String requested = paths.size() == 1 ? arguments.optionalString("file_name") : null;
        SandboxSession session;
        try {
            session = sessions.session(context);
        } catch (CodeSessions.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
        List<String> handedOut = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String path : paths) {
            String name = name(path, requested);
            String type = ChatFileService.typeOfName(name);
            String full = full(context, type);
            if (full != null) {
                notes.add(path + ": " + full);
                continue;
            }
            SandboxSession.FileResult file = session.file(path, ChatFileService.MAX_FILE_BYTES);
            if (file.error() != null) {
                notes.add(path + ": " + file.error());
                continue;
            }
            ChatFileService.PendingFile stored = files.createReturned(context.tenant(), name, file.content(), type,
                    context.files() == ToolContext.Files.KEPT);
            context.createdFiles().add(stored);
            handedOut.add(stored.fileName());
        }
        if (handedOut.isEmpty()) {
            return ToolResult.error(String.join("; ", notes));
        }
        String text = handedOut.size() == 1 ? context.fileNote(handedOut.getFirst()) : several(context, handedOut);
        return ToolResult.text(notes.isEmpty() ? text : text + "\nNot handed out: " + String.join("; ", notes));
    }

    /** Why a file of this type cannot be handed out any more in this answer - null while it can. */
    private static String full(ToolContext context, String type) {
        if (type.startsWith("image/")) {
            return context.createdImages() >= MAX_IMAGES_PER_ANSWER
                    ? "at most " + MAX_IMAGES_PER_ANSWER + " images per answer" : null;
        }
        return context.createdFilesExceptImages() >= CreateFileTool.MAX_FILES_PER_ANSWER
                ? "at most " + CreateFileTool.MAX_FILES_PER_ANSWER + " other files per answer" : null;
    }

    /** What the model is told of several files - like ToolContext.fileNote, so it writes no link to any. */
    private static String several(ToolContext context, List<String> names) {
        String listed = handedOutList(names);
        return context.files() == ToolContext.Files.DELIVERED
                ? listed + " go to the asker with your answer - do not repeat their content, write no link or path to them."
                : listed + " were handed out. The application shows them below the answer (images as one sequence with a "
                        + "slider) - do not write a link or path to them.";
    }

    private static String handedOutList(List<String> names) {
        return names.size() + " files (\"" + String.join("\", \"", names) + "\")";
    }

    /** The name asked for - with the file's extension when it has none, which decides its type -, else the file's own. */
    static String name(String path, String requested) {
        String own = path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
        if (requested == null) {
            return own;
        }
        int dot = own.lastIndexOf('.');
        return requested.contains(".") || dot < 0 ? requested : requested + own.substring(dot);
    }
}
