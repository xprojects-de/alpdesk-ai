package de.alpdesk.controller.chat;

import de.alpdesk.dto.chat.ChatFileResponse;
import de.alpdesk.dto.chat.ChatRequest;
import de.alpdesk.dto.chat.ChatResponse;
import de.alpdesk.dto.chat.ChatUploadResponse;
import de.alpdesk.dto.chat.ConversationResponse;
import de.alpdesk.dto.chat.MessageResponse;
import de.alpdesk.entities.chat.ConversationOwner;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.chat.ChatFileService;
import de.alpdesk.dto.assistant.ChatTemplateOption;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.chat.ChatService;
import de.alpdesk.services.chat.PreparedChat;
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
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.reactivestreams.Publisher;

import java.util.List;

/** The tenant user's chat: its own conversations and their files only - another user's answer 404. */
@Secured(Roles.TENANT_USER)
@Tag(name = "Users")
@SecurityRequirement(name = "UserAuth")
@Controller("/tenant/chat")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ChatController {

    private final ChatService service;
    private final ChatFileService chatFiles;
    private final AssistantService assistant;

    public ChatController(ChatService service, ChatFileService chatFiles, AssistantService assistant) {
        this.service = service;
        this.chatFiles = chatFiles;
        this.assistant = assistant;
    }

    /**
     * Asks the chosen model and streams the answer as server-sent events: "thinking" (pieces of the model's reasoning,
     * where its provider hands it over - shown, never stored) while it thinks, "delta" (pieces of the text) as the model
     * writes, "sources" (the documents it draws on so far) whenever a search finds new ones, "tool" (a tool about
     * to run, with its connected software - shown to the user, and a sign of life while tools take their time), finally
     * "done" (the stored conversation, question and answer, a ChatResponse) - or "error" ({message}) when the provider fails, with nothing stored. Invalid input, another
     * user's conversation or a missing key are refused before the stream starts, as plain HTTP errors - JSON, so the
     * client must accept both: {@code Accept: text/event-stream, application/json} (with the event stream alone,
     * Micronaut finds no error route that produces it and answers a bare 500).
     */
    @Consumes(MediaType.APPLICATION_JSON)
    @Post(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<Object>> stream(Authentication authentication, @Body ChatRequest request) {
        return events(service.prepare(TenantContext.tenantId(authentication), TenantContext.userId(authentication),
                request.conversationId(), request.modelId(), request.templateId(), request.message(),
                request.excludedConnectionIds(), request.excludedSkillIds()));
    }

    /**
     * Runs a changing tool call of the company's software the chat showed (docs/architecture/mcp.md "Changing tools"),
     * then streams how the model goes on, like /stream ("done" carries no question). 404 for another user's call, 409
     * when it was decided or expired, or the tool is no longer turned on for the user - before the stream starts.
     */
    @Post(value = "/actions/{id}/run", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<Object>> runAction(Authentication authentication, @PathVariable Long id) {
        return events(service.decide(TenantContext.tenantId(authentication), TenantContext.userId(authentication), id, true));
    }

    /** Cancels such a call - it is not run - and streams how the model goes on, as at run. */
    @Post(value = "/actions/{id}/cancel", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<Object>> cancelAction(Authentication authentication, @PathVariable Long id) {
        return events(service.decide(TenantContext.tenantId(authentication), TenantContext.userId(authentication), id, false));
    }

    private Publisher<Event<Object>> events(PreparedChat chat) {
        return ChatEvents.stream(sink -> {
            // A closed stream (a stop, a logout) stops an agent's answer; the model's goes on and is stored.
            sink.onCancel(chat::stop);
            service.stream(chat,
                part -> ChatEvents.delta(sink, part),
                thinking -> ChatEvents.thinking(sink, thinking),
                sources -> ChatEvents.sources(sink, sources),
                step -> ChatEvents.tool(sink, step),
                result -> ChatEvents.done(sink, ChatResponse.from(result)),
                failure -> ChatEvents.error(sink, failure));
        });
    }

    /** The templates the user may choose for a question - names only, their instructions are the tenant admins'. */
    @Produces(MediaType.APPLICATION_JSON)
    @Get("/templates")
    public List<ChatTemplateOption> templates(Authentication authentication) {
        return assistant.templates(TenantContext.tenantId(authentication)).stream().map(ChatTemplateOption::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/conversations")
    public List<ConversationResponse> conversations(Authentication authentication) {
        return service.findConversations(TenantContext.tenantId(authentication), owner(authentication))
                .stream().map(ConversationResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/conversations/{id}/messages")
    public List<MessageResponse> messages(Authentication authentication, @PathVariable Long id) {
        return service.findMessages(TenantContext.tenantId(authentication), owner(authentication), id)
                .stream().map(MessageResponse::from).toList();
    }

    /**
     * Adds files to one of the user's conversations, or to a new one without conversationId (multipart: "files", one
     * or more, and optionally "conversationId"). The files are not embedded - every question of the conversation is
     * sent with them.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/files")
    public HttpResponse<ChatUploadResponse> upload(Authentication authentication, @Part("files") Publisher<CompletedFileUpload> files,
                                                   @Part("conversationId") @Nullable Long conversationId) {
        return HttpResponse.created(ChatUploadResponse.from(chatFiles.upload(TenantContext.tenantId(authentication),
                owner(authentication), conversationId, ChatFiles.read(files))));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/conversations/{id}/files")
    public List<ChatFileResponse> files(Authentication authentication, @PathVariable Long id) {
        return chatFiles.findByConversation(TenantContext.tenantId(authentication), owner(authentication), id)
                .stream().map(ChatFileResponse::from).toList();
    }

    @Get("/files/{id}/content")
    public HttpResponse<StreamedFile> download(Authentication authentication, @PathVariable Long id) {
        return ChatFiles.download(chatFiles.open(TenantContext.tenantId(authentication), owner(authentication), id));
    }

    @Delete("/files/{id}")
    public HttpResponse<?> deleteFile(Authentication authentication, @PathVariable Long id) {
        return chatFiles.delete(TenantContext.tenantId(authentication), owner(authentication), id)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    @Delete("/conversations/{id}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long id) {
        return service.deleteConversation(TenantContext.tenantId(authentication), owner(authentication), id)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** The tenant user's own conversations. */
    private static ConversationOwner owner(Authentication authentication) {
        return ConversationOwner.user(TenantContext.userId(authentication));
    }
}
