package de.alpdesk.controller.assistant;

import de.alpdesk.dto.assistant.AssistantSettings;
import de.alpdesk.dto.assistant.ChatTemplateRequest;
import de.alpdesk.dto.assistant.ChatTemplateResponse;
import de.alpdesk.dto.assistant.ModelOption;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.assistant.AssistantService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** The assistant settings and chat templates of the tenant admin's own tenant - like the admin's (AssistantAdminController). */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/assistant")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantAdminAssistantController {

    private final AssistantService service;

    public TenantAdminAssistantController(AssistantService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public AssistantSettings settings(Authentication authentication) {
        return service.settings(TenantContext.tenantId(authentication));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put
    public AssistantSettings saveSettings(Authentication authentication, @Body AssistantSettings request) {
        return service.saveSettings(TenantContext.tenantId(authentication), request);
    }

    /** The chat models the tenant may use - to choose its default model from. */
    @Produces(MediaType.APPLICATION_JSON)
    @Get("/models")
    public List<ModelOption> models(Authentication authentication) {
        return service.chatModels(TenantContext.tenantId(authentication));
    }

    /** The image models the tenant may use - to choose the chat's from. */
    @Produces(MediaType.APPLICATION_JSON)
    @Get("/image-models")
    public List<ModelOption> imageModels(Authentication authentication) {
        return service.imageModels(TenantContext.tenantId(authentication));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/templates")
    public List<ChatTemplateResponse> templates(Authentication authentication) {
        return service.templates(TenantContext.tenantId(authentication)).stream().map(ChatTemplateResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/templates")
    public HttpResponse<ChatTemplateResponse> create(Authentication authentication, @Body ChatTemplateRequest request) {
        return HttpResponse.created(ChatTemplateResponse.from(
                service.createTemplate(TenantContext.tenantId(authentication), request)));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/templates/{templateId}")
    public HttpResponse<ChatTemplateResponse> update(Authentication authentication, @PathVariable Long templateId,
                                                     @Body ChatTemplateRequest request) {
        return service.updateTemplate(TenantContext.tenantId(authentication), templateId, request)
                .map(template -> HttpResponse.ok(ChatTemplateResponse.from(template)))
                .orElseGet(HttpResponse::notFound);
    }

    @Delete("/templates/{templateId}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long templateId) {
        return service.deleteTemplate(TenantContext.tenantId(authentication), templateId)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
