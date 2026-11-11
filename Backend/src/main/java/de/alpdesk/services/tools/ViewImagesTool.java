package de.alpdesk.services.tools;

import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.DocumentImages;
import de.alpdesk.services.sandbox.SandboxSession;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.micronaut.core.annotation.Order;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the model images its code wrote in the sandbox (docs/architecture/code-execution.md "Videos"): frames cut out of
 * a video, a chart, a still it drew on - it looks, then decides where to look closer. The code tools answer text only;
 * without this the model would have to guess what a video shows.
 */
@Singleton
@Order(10)
public class ViewImagesTool implements ChatTool {

    public static final String NAME = "view_images";
    /** Images of one call - a frame of 1280x720 costs a model about 1 200 tokens. */
    static final int MAX_IMAGES = 12;
    /**
     * Images of one answer: each later round sends every image seen again, and a provider refuses a request with too
     * many (Anthropic at 100) - the whole answer would fail.
     */
    static final int MAX_IMAGES_PER_ANSWER = 60;
    /** One image file - a frame is a few hundred kilobytes. */
    static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;

    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name(NAME)
            .description("Shows you images your code wrote with run_python or run_shell (PNG, JPEG, GIF, BMP) - e.g. "
                    + "frames cut out of a video with ffmpeg, or a still you drew on. At most " + MAX_IMAGES + " per "
                    + "call and " + MAX_IMAGES_PER_ANSWER + " per answer; each comes with its size in pixels, the "
                    + "coordinates to draw at.")
            .parameters(JsonObjectSchema.builder()
                    .addProperty("paths", JsonArraySchema.builder()
                            .description("The images' paths relative to the directory your code runs in, e.g. "
                                    + "out/frame_001.jpg")
                            .items(new JsonStringSchema())
                            .build())
                    .required("paths")
                    .build())
            .build();

    private final CodeSessions sessions;
    private final JsonMapper json;

    ViewImagesTool(CodeSessions sessions, JsonMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    public ToolSpecification specification() {
        return SPECIFICATION;
    }

    @Override
    public boolean availableFor(ToolContext context) {
        return sessions.available(context);
    }

    /** Waits for the sandbox - no transaction is held. */
    @Override
    public ToolResult execute(ToolContext context, ToolExecutionRequest request) {
        List<String> paths = ToolArguments.of(json, request).requiredDistinctStrings("paths");
        if (paths.size() > MAX_IMAGES) {
            return ToolResult.error("at most " + MAX_IMAGES + " images per call");
        }
        if (context.imagesViewed() + paths.size() > MAX_IMAGES_PER_ANSWER) {
            return ToolResult.error("at most " + MAX_IMAGES_PER_ANSWER + " images per answer, "
                    + (MAX_IMAGES_PER_ANSWER - context.imagesViewed()) + " left - answer with what you have seen");
        }
        SandboxSession session;
        try {
            session = sessions.session(context);
        } catch (CodeSessions.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
        List<ToolResult.ToolAttachment> images = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String path : paths) {
            SandboxSession.FileResult file = session.file(path, MAX_IMAGE_BYTES);
            if (file.error() != null) {
                notes.add(path + ": " + file.error());
                continue;
            }
            try {
                images.add(ToolResult.ToolAttachment.jpeg(label(path, file.content()), DocumentImages.imageAsJpeg(file.content())));
            } catch (ServiceException e) {
                notes.add(path + ": not an image that can be shown (PNG, JPEG, GIF or BMP) - " + e.getMessage());
            }
        }
        context.imagesViewed(images.size());
        String text = images.isEmpty() ? "Error: no image could be shown." : images.size() + " image(s) follow the tool results.";
        return new ToolResult(notes.isEmpty() ? text : text + "\n" + String.join("\n", notes), images);
    }

    /** The path and the size in pixels - the model draws in the file's own pixels, also when it is shown smaller. */
    private static String label(String path, byte[] content) {
        Dimension size = DocumentImages.size(content);
        if (size == null) {
            return "\"" + path + "\"";
        }
        String pixels = size.width + "x" + size.height + " pixels";
        return "\"" + path + "\" (" + pixels + (Math.max(size.width, size.height) > DocumentImages.MAX_EDGE
                ? ", shown smaller - give positions in its own " + pixels : "") + ")";
    }
}
