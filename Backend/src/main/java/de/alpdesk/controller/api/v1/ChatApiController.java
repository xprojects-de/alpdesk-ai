package de.alpdesk.controller.api.v1;

import de.alpdesk.dto.chat.ChoiceResponse;
import de.alpdesk.dto.chat.SkillChoiceResponse;
import de.alpdesk.services.mcp.McpConnectionService;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import de.alpdesk.controller.chat.ChatEvents;
import de.alpdesk.controller.chat.ChatFiles;
import de.alpdesk.dto.api.v1.ApiModelResponse;
import de.alpdesk.dto.chat.ChatFileResponse;
import de.alpdesk.dto.chat.ChatRequest;
import de.alpdesk.dto.chat.ChatResponse;
import de.alpdesk.dto.chat.ChatUploadResponse;
import de.alpdesk.dto.chat.ConversationResponse;
import de.alpdesk.dto.chat.SoftwareChoiceResponse;
import de.alpdesk.services.mcp.McpTools;
import de.alpdesk.dto.chat.MessageResponse;
import de.alpdesk.dto.common.ErrorResponse;
import de.alpdesk.entities.ai.ModelKind;
import de.alpdesk.entities.chat.ConversationOwner;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.chat.ChatFileService;
import de.alpdesk.dto.assistant.ChatTemplateOption;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.chat.ChatService;
import de.alpdesk.services.chat.PreparedChat;
import de.alpdesk.services.chat.SentFile;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.TenantService;
import de.alpdesk.services.tools.ToolContext;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.reactivestreams.Publisher;

import java.util.List;

/**
 * The tenant's assistant for the client software (decided 6 October 2026): the same chat as the users', drawing on the
 * tenant's documents and the files of the conversation (the assistant may create files for download). Its
 * conversations belong to the API key that started them (decided 9 October 2026) and are seen by no user and no other
 * key; the users' conversations are not seen here.
 */
@Secured(Roles.TENANT)
@Controller("/api/v1")
@ExecuteOn(TaskExecutors.BLOCKING)
@Tag(name = "API v1")
public class ChatApiController {

    private final ChatService chat;
    private final ChatFileService chatFiles;
    private final TenantService tenants;
    private final TenantModelService models;
    private final ProviderKeyService keys;
    private final AssistantService assistant;
    private final McpTools software;
    private final McpConnectionService connections;

    public ChatApiController(ChatService chat, ChatFileService chatFiles, TenantService tenants, TenantModelService models,
                             ProviderKeyService keys, AssistantService assistant, McpTools software,
                             McpConnectionService connections) {
        this.connections = connections;
        this.software = software;
        this.chat = chat;
        this.chatFiles = chatFiles;
        this.tenants = tenants;
        this.models = models;
        this.keys = keys;
        this.assistant = assistant;
    }

    /**
     * The connected software the key may use - names and ids a question may leave out (excludedConnectionIds; decided
     * 5 November 2026). Not the tools: the client software never calls them itself (docs/architecture/mcp.md).
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Get("/software")
    @Operation(summary = "The tenant's connected software this key's questions use - a question may leave some out with "
            + "excludedConnectionIds. For a tenant answering through its A2A agent the agent's skills instead (ids "
            + "\"skill:<id>\") - a question leaves them out with excludedSkillIds", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The connected software by name, or the agent's skills", content = @Content(
            array = @ArraySchema(schema = @Schema(oneOf = {SoftwareChoiceResponse.class, SkillChoiceResponse.class}))))
    public List<ChoiceResponse> software(Authentication authentication) {
        Long tenantId = TenantContext.tenantId(authentication);
        if (connections.answeringAgent(tenantId).isPresent()) {
            return connections.skillsOfAnsweringAgent(tenantId).stream()
                    .<ChoiceResponse>map(SkillChoiceResponse::from).toList();
        }
        return software.connectionsOfApiKey(tenantId, TenantContext.apiKeyId(authentication)).stream()
                .<ChoiceResponse>map(SoftwareChoiceResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/models")
    @Operation(summary = "The chat models the tenant's assistant can use", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The active chat models the tenant may use", content = @Content(schema = @Schema(implementation = ApiModelResponse.class)))
    public List<ApiModelResponse> models(Authentication authentication) {
        Tenant tenant = tenants.findById(TenantContext.tenantId(authentication))
                .orElseThrow(() -> ServiceException.notFound("tenant.notFound", "Tenant not found"));
        return models.findUsable(tenant.id()).stream()
                .filter(model -> model.kind() == ModelKind.CHAT)
                .map(model -> new ApiModelResponse(model.id(), model.provider().name(), model.displayName(),
                        keys.sourceOf(tenant, model).isPresent()))
                .toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/chat")
    @Operation(summary = "Ask the assistant - it answers from the tenant's documents; may take up to five minutes. With an "
            + "API key that keeps no history every question is answered on its own and nothing is stored but the usage; "
            + "such a question may bring text files (files). The reading tools of the tenant's connected software that a "
            + "tenant admin opened to the client software are used too", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The answer", content = @Content(schema = @Schema(implementation = ChatResponse.class)))
    @ApiResponse(responseCode = "400", description = "Empty question without a template, or too long, unknown model or template, a connected software to leave out that the key does not use, files too large or with a key that keeps history, a conversation with a key that keeps none", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such conversation of the client software", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "No API key for the model's provider; no model given and no default model", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "The AI provider failed or refused the request", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ChatResponse ask(Authentication authentication, @Body ChatRequest request) {
        Long tenantId = TenantContext.tenantId(authentication);
        List<SentFile> files = sentFiles(request);
        if (!TenantContext.keepsHistory(authentication)) {
            requireNoConversation(request);
            return ChatResponse.from(chat.answerOnce(tenantId, ToolContext.Asker.CLIENT_SOFTWARE,
                    TenantContext.apiKeyId(authentication), request.modelId(), request.templateId(), request.message(),
                    files, request.excludedConnectionIds(), request.excludedSkillIds()));
        }
        requireNoFiles(files);
        return ChatResponse.from(chat.ask(tenantId, TenantContext.apiKeyId(authentication), request.conversationId(),
                request.modelId(), request.templateId(), request.message(), request.excludedConnectionIds(),
                request.excludedSkillIds()));
    }

    /**
     * As POST /api/v1/chat, the answer read as it comes (decided 13 October 2026): the same request and rules, the
     * events of the users' chat. What is refused before the answer starts (400, 404, 409) comes as a plain JSON error,
     * so the client accepts both.
     */
    @Produces(MediaType.TEXT_EVENT_STREAM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/chat/stream")
    @Operation(summary = "Ask the assistant and read the answer as it comes - the request and rules of POST /api/v1/chat, "
            + "answered as server-sent events: \"thinking\" (a piece of the model's reasoning, {text}), \"delta\" (a piece "
            + "of the answer, {text}), \"sources\" (the documents found so far, {sources}), \"tool\" (a tool call before it "
            + "runs, {name, label, ...}), then \"done\" (the whole answer as POST /api/v1/chat returns it) or \"error\" "
            + "(the error, {message, code, params}). While a tool runs for long, a comment line \": keep-alive\" every 20 "
            + "seconds keeps the connection open - no event, skip it. What is refused before the answer starts answers "
            + "as plain JSON - send Accept: text/event-stream, application/json",
            security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The events, ending with \"done\" or \"error\"")
    @ApiResponse(responseCode = "400", description = "As POST /api/v1/chat", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such conversation of the client software", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "As POST /api/v1/chat", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public Publisher<Event<Object>> stream(Authentication authentication, @Body ChatRequest request) {
        Long tenantId = TenantContext.tenantId(authentication);
        Long apiKeyId = TenantContext.apiKeyId(authentication);
        List<SentFile> files = sentFiles(request);
        if (!TenantContext.keepsHistory(authentication)) {
            requireNoConversation(request);
            PreparedChat once = chat.prepareOnce(tenantId, ToolContext.Asker.CLIENT_SOFTWARE, apiKeyId,
                    request.modelId(), request.templateId(), request.message(), files, request.excludedConnectionIds(),
                    request.excludedSkillIds());
            return ChatEvents.stream(sink -> {
                sink.onCancel(once::stop);
                chat.streamOnce(once,
                    part -> ChatEvents.delta(sink, part),
                    thinking -> ChatEvents.thinking(sink, thinking),
                    sources -> ChatEvents.sources(sink, sources),
                    step -> ChatEvents.tool(sink, step),
                    answer -> ChatEvents.done(sink, ChatResponse.from(answer)),
                    failure -> ChatEvents.error(sink, failure));
            });
        }
        requireNoFiles(files);
        PreparedChat prepared = chat.prepareForApiKey(tenantId, apiKeyId, request.conversationId(), request.modelId(),
                request.templateId(), request.message(), request.excludedConnectionIds(), request.excludedSkillIds());
        return ChatEvents.stream(sink -> {
            sink.onCancel(prepared::stop);
            chat.stream(prepared,
                part -> ChatEvents.delta(sink, part),
                thinking -> ChatEvents.thinking(sink, thinking),
                sources -> ChatEvents.sources(sink, sources),
                step -> ChatEvents.tool(sink, step),
                result -> ChatEvents.done(sink, ChatResponse.from(result)),
                failure -> ChatEvents.error(sink, failure));
        });
    }

    private static List<SentFile> sentFiles(ChatRequest request) {
        return request.files() == null ? List.of()
                // A null entry ("files": [null]) is a file without name and content - refused as such (400), not a 500.
                : request.files().stream()
                        .map(file -> file == null ? new SentFile(null, null) : new SentFile(file.name(), file.content()))
                        .toList();
    }

    private static void requireNoConversation(ChatRequest request) {
        if (request.conversationId() != null) {
            throw ServiceException.badRequest("chat.noHistoryConversation",
                    "This API key keeps no history - leave conversationId out");
        }
    }

    private static void requireNoFiles(List<SentFile> files) {
        if (!files.isEmpty()) {
            throw ServiceException.badRequest("chat.filesNeedNoHistory",
                    "Files come with a question only with an API key that keeps no history - "
                    + "add them to a conversation with POST /api/v1/chat/files");
        }
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/templates")
    @Operation(summary = "The templates of the tenant's assistant - each answers with instructions and settings of its "
            + "own; send its id as templateId with a question", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The templates by name", content = @Content(schema = @Schema(implementation = ChatTemplateOption.class)))
    public List<ChatTemplateOption> templates(Authentication authentication) {
        return assistant.templates(TenantContext.tenantId(authentication)).stream().map(ChatTemplateOption::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/chat/conversations")
    @Operation(summary = "The client software's conversations, last used first", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The conversations", content = @Content(schema = @Schema(implementation = ConversationResponse.class)))
    public List<ConversationResponse> conversations(Authentication authentication) {
        return chat.findConversations(TenantContext.tenantId(authentication), owner(authentication)).stream()
                .map(ConversationResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/chat/conversations/{id}/messages")
    @Operation(summary = "The messages of a conversation, oldest first", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The messages", content = @Content(schema = @Schema(implementation = MessageResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such conversation of the client software")
    public List<MessageResponse> messages(Authentication authentication, @PathVariable Long id) {
        return chat.findMessages(TenantContext.tenantId(authentication), owner(authentication), id).stream()
                .map(MessageResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/chat/files")
    @Operation(summary = "Add files to a conversation, or start one with them (multipart: \"files\", one or more, and "
            + "optionally \"conversationId\"). Documents (PDF - also scanned -, DOCX, ODT, RTF, TXT, Markdown, HTML, PPTX, "
            + "XLSX, ODS, CSV), images (PNG, JPEG, GIF, BMP) and audio recordings (transcribed) up to 50 MB; a video up to "
            + "500 MB only where the tenant's code execution is the video sandbox. Every question lists them and the "
            + "assistant reads what it needs (a file at most 2000000 characters of text, a scan at most 20 pages). Files "
            + "expire after the installation's retention (by default a video after 8 hours, every other file after 14 "
            + "days): still listed, marked \"expired\", their download answers 404",
            security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "201", description = "The files and their conversation", content = @Content(schema = @Schema(implementation = ChatUploadResponse.class)))
    @ApiResponse(responseCode = "400", description = "Empty or unreadable file, limits exceeded; an API key that keeps no history", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such conversation of the client software")
    @ApiResponse(responseCode = "409", description = "A video, but the tenant's code execution is not the video sandbox", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "415", description = "The file type is not supported", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public HttpResponse<ChatUploadResponse> upload(Authentication authentication, @Part("files") Publisher<CompletedFileUpload> files,
                                                   @Part("conversationId") @Nullable Long conversationId) {
        // It would store a conversation the key can never ask in.
        if (!TenantContext.keepsHistory(authentication)) {
            throw ServiceException.badRequest("chatFile.noHistory",
                    "This API key keeps no history - send the files with the question "
                    + "(POST /api/v1/chat, \"files\")");
        }
        return HttpResponse.created(ChatUploadResponse.from(chatFiles.upload(TenantContext.tenantId(authentication), owner(authentication),
                conversationId, ChatFiles.read(files))));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/chat/conversations/{id}/files")
    @Operation(summary = "The files of a conversation - uploaded ones and the ones the assistant created", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The files", content = @Content(schema = @Schema(implementation = ChatFileResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such conversation of the client software")
    public List<ChatFileResponse> files(Authentication authentication, @PathVariable Long id) {
        return chatFiles.findByConversation(TenantContext.tenantId(authentication), owner(authentication), id).stream()
                .map(ChatFileResponse::from).toList();
    }

    @Get("/chat/files/{id}/content")
    @Operation(summary = "Download a file of a conversation", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The file")
    @ApiResponse(responseCode = "404", description = "No such file of the client software, or it expired")
    public HttpResponse<StreamedFile> download(Authentication authentication, @PathVariable Long id) {
        return ChatFiles.download(chatFiles.open(TenantContext.tenantId(authentication), owner(authentication), id));
    }

    @Delete("/chat/files/{id}")
    @Operation(summary = "Delete a file of a conversation", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such file of the client software")
    public HttpResponse<?> deleteFile(Authentication authentication, @PathVariable Long id) {
        return chatFiles.delete(TenantContext.tenantId(authentication), owner(authentication), id) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    @Delete("/chat/conversations/{id}")
    @Operation(summary = "Delete a conversation with its messages", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such conversation of the client software")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long id) {
        return chat.deleteConversation(TenantContext.tenantId(authentication), owner(authentication), id)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** The conversations of the asking API key - another key's may quote knowledge areas this one does not find. */
    private static ConversationOwner owner(Authentication authentication) {
        return ConversationOwner.apiKey(TenantContext.apiKeyId(authentication));
    }
}
