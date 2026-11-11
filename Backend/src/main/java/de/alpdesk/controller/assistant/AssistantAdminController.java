package de.alpdesk.controller.assistant;

import de.alpdesk.dto.assistant.AssistantSettings;
import de.alpdesk.dto.assistant.ChatTemplateRequest;
import de.alpdesk.dto.assistant.ChatTemplateResponse;
import de.alpdesk.dto.assistant.ModelOption;
import de.alpdesk.security.Roles;
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
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** A tenant's assistant settings and chat templates, kept by the admin - like its tenant admins (TenantAdminAssistantController). */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}/assistant")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AssistantAdminController {

    private final AssistantService service;

    public AssistantAdminController(AssistantService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public AssistantSettings settings(@PathVariable Long tenantId) {
        return service.settings(tenantId);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put
    public AssistantSettings saveSettings(@PathVariable Long tenantId, @Body AssistantSettings request) {
        return service.saveSettings(tenantId, request);
    }

    /** The chat models the tenant may use - to choose its default model from. */
    @Produces(MediaType.APPLICATION_JSON)
    @Get("/models")
    public List<ModelOption> models(@PathVariable Long tenantId) {
        return service.chatModels(tenantId);
    }

    /** The image models the tenant may use - to choose the chat's from. */
    @Produces(MediaType.APPLICATION_JSON)
    @Get("/image-models")
    public List<ModelOption> imageModels(@PathVariable Long tenantId) {
        return service.imageModels(tenantId);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/templates")
    public List<ChatTemplateResponse> templates(@PathVariable Long tenantId) {
        return service.templates(tenantId).stream().map(ChatTemplateResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/templates")
    public HttpResponse<ChatTemplateResponse> create(@PathVariable Long tenantId, @Body ChatTemplateRequest request) {
        return HttpResponse.created(ChatTemplateResponse.from(service.createTemplate(tenantId, request)));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/templates/{templateId}")
    public HttpResponse<ChatTemplateResponse> update(@PathVariable Long tenantId, @PathVariable Long templateId,
                                                     @Body ChatTemplateRequest request) {
        return service.updateTemplate(tenantId, templateId, request)
                .map(template -> HttpResponse.ok(ChatTemplateResponse.from(template)))
                .orElseGet(HttpResponse::notFound);
    }

    @Delete("/templates/{templateId}")
    public HttpResponse<?> delete(@PathVariable Long tenantId, @PathVariable Long templateId) {
        return service.deleteTemplate(tenantId, templateId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
