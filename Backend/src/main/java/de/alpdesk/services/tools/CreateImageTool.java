package de.alpdesk.services.tools;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.chat.ConversationFile;
import de.alpdesk.services.ai.AiModels;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.ai.UsageService;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.chat.ChatFileService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates an image with the tenant's image model (docs/roadmap.md point 25): a new one from the instruction alone, or
 * one made of images of this conversation - changed, or combined. The result is a file of the conversation like one of
 * create_file: the next question lists it, so the user improves it round by round. Only the conversation's images can
 * be named, never documents of the company knowledge.
 */
@Singleton
@Order(7)
public class CreateImageTool implements ChatTool {

    public static final String NAME = "create_image";
    /** Calls of the image model one answer may make - each is billed, also one that created no image (refused). */
    static final int MAX_IMAGE_CALLS_PER_ANSWER = 3;
    /**
     * An image larger than this goes as the model's JPEG copy (2048 pixels at most): Gemini takes about 20 MB per
     * request, five images base64-encoded included. That holds for an image the image model created too: Gemini's PNG
     * may be larger (2.7 MB seen at 1536 x 672 pixels), then the next round gets the copy (JPEG quality 0.85) - the
     * file the user downloads stays Gemini's PNG. Sending created images always as they are waits for a visible loss.
     */
    static final int MAX_ORIGINAL_BYTES = 2_500_000;
    private static final Set<String> SENT_AS_THEY_ARE = Set.of("image/png", "image/jpeg");
    private static final Pattern VERSION = Pattern.compile("(.*)-v(\\d{1,4})");

    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name(NAME)
            .description("Creates an image with an image model and shows it to the user below the answer: a new one "
                    + "from the instruction alone, or one made of images of this conversation (see the list of files) "
                    + "- changed, or combined. Call it only when the user asks for an image - a picture, a drawing, a "
                    + "changed or combined photo - each call costs. To improve an image created before, give its "
                    + "file_id.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("instruction", "Everything the image model needs, complete and concrete: what "
                            + "to show or change, the style, and any text to appear in the image word for word. It "
                            + "does not see this conversation.")
                    .addProperty("file_ids", JsonArraySchema.builder()
                            .description("Optional: the file_ids of images of this conversation to change or combine, "
                                    + "the one to change first")
                            .items(new JsonIntegerSchema())
                            .build())
                    .addStringProperty("file_name", "Optional: the name of the new image, e.g. einladung.png")
                    .required("instruction")
                    .build())
            .build();

    private final AssistantService assistants;
    private final ProviderKeyService keys;
    private final AiModels aiModels;
    private final UsageService usage;
    private final ChatFileService files;
    private final JsonMapper json;

    public CreateImageTool(AssistantService assistants, ProviderKeyService keys, AiModels aiModels, UsageService usage,
                           ChatFileService files, JsonMapper json) {
        this.assistants = assistants;
        this.keys = keys;
        this.aiModels = aiModels;
        this.usage = usage;
        this.files = files;
        this.json = json;
    }

    /**
     * Where the image goes somewhere - kept in the conversation, or sent with ask_assistant's answer - and the tenant
     * has an image model (AssistantService.imageModel).
     */
    @Override
    public boolean availableFor(ToolContext context) {
        return context.takesFiles() && assistants.imageModel(context.tenant().id()).isPresent();
    }

    @Override
    public ToolSpecification specification() {
        return SPECIFICATION;
    }

    /**
     * Calls the image model - outside of any transaction, it takes seconds to a minute. The call is billed even when it
     * created no image (the model refused): the usage is recorded either way, the model gets the image model's reason.
     */
    @Override
    public ToolResult execute(ToolContext context, ToolExecutionRequest request) {
        AiModel model = assistants.imageModel(context.tenant().id()).orElse(null);
        if (model == null) {
            return ToolResult.error("there is no image model");
        }
        if (context.imageCalls() >= MAX_IMAGE_CALLS_PER_ANSWER) {
            return ToolResult.error("at most " + MAX_IMAGE_CALLS_PER_ANSWER + " image model calls per answer");
        }
        ToolArguments arguments = ToolArguments.of(json, request);
        String instruction = arguments.requiredString("instruction");
        List<Long> fileIds = new ArrayList<>(new LinkedHashSet<>(arguments.optionalLongs("file_ids")));
        int imagesPerCall = aiModels.imagesPerCall(model);
        if (fileIds.size() > imagesPerCall) {
            return ToolResult.error(model.displayName() + " takes at most " + imagesPerCall + " images at once");
        }
        List<ConversationFile> sources = new ArrayList<>();
        for (Long fileId : fileIds) {
            Optional<ConversationFile> file = context.conversationId() == null ? Optional.empty()
                    : files.findInConversation(context.dataSource(), context.conversationId(), fileId);
            if (file.isEmpty() || !file.get().contentType().startsWith("image/")) {
                return ToolResult.error("file_id " + fileId + " is no image of this conversation");
            }
            if (file.get().isExpired()) {
                return ToolResult.error("file_id " + fileId + " " + file.get().expiredNote());
            }
            sources.add(file.get());
        }

        ResolvedKey key = keys.resolve(context.tenant(), model);
        context.imageCalled();
        AiModels.CreatedImage created = aiModels.createImage(model, key.apiKey(), instruction, images(sources));
        usage.record(context.tenant().id(), context.userId(), model, key.source(), created.usage(), instruction.length());
        if (!created.hasImage()) {
            return ToolResult.error("the image model created no image"
                    + (created.text().isEmpty() ? "" : " - it answered: " + created.text()));
        }
        ChatFileService.PendingFile file = files.createImage(context.tenant(),
                resultName(arguments.optionalString("file_name"), sources, created.mimeType()), created.content(),
                created.mimeType());
        context.createdFiles().add(file);
        if (context.files() == ToolContext.Files.DELIVERED) {
            return ToolResult.text(context.fileNote(file.fileName()));
        }
        // The models otherwise like to add a made-up link (seen with create_file).
        return ToolResult.text("The image \"" + file.fileName() + "\" was created. The application shows it below the "
                + "answer - do not write a link or path to it. From the next question on it is in the list of files, "
                + "to be changed further.");
    }

    /** Each image as it is where it is small enough, else the model's JPEG copy. */
    private List<Image> images(List<ConversationFile> sources) {
        List<Image> images = new ArrayList<>();
        for (ConversationFile file : sources) {
            boolean asItIs = SENT_AS_THEY_ARE.contains(file.contentType()) && file.sizeBytes() <= MAX_ORIGINAL_BYTES;
            byte[] content = asItIs ? files.content(file) : files.storedImages(file).getFirst();
            images.add(Image.builder().base64Data(Base64.getEncoder().encodeToString(content))
                    .mimeType(asItIs ? file.contentType() : "image/jpeg").build());
        }
        return images;
    }

    /**
     * The name the model gave, else the next version of the first image ("haus.png" → "haus-v2.png" → "haus-v3.png"),
     * else "bild" - with the extension of what the image model sent.
     */
    static String resultName(@Nullable String requested, List<ConversationFile> sources, String mimeType) {
        String extension = mimeType.equals("image/jpeg") ? ".jpg" : mimeType.equals("image/webp") ? ".webp" : ".png";
        if (requested != null) {
            return withoutExtension(requested) + extension;
        }
        if (sources.isEmpty()) {
            return "bild" + extension;
        }
        String base = withoutExtension(sources.getFirst().fileName());
        Matcher version = VERSION.matcher(base);
        return version.matches() ? version.group(1) + "-v" + (Integer.parseInt(version.group(2)) + 1) + extension
                : base + "-v2" + extension;
    }

    private static String withoutExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
