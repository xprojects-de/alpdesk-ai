package de.alpdesk.controller.document;

import de.alpdesk.dto.document.KnowledgeSourceRequest;
import de.alpdesk.dto.document.KnowledgeSourceResponse;
import de.alpdesk.dto.document.SourceFileResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.services.document.KnowledgeSourceService;
import io.micronaut.core.annotation.Nullable;
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
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** The admin keeps a tenant's knowledge sources - like its tenant admins (KnowledgeSourceController). */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}/knowledge-sources")
@ExecuteOn(TaskExecutors.BLOCKING)
public class KnowledgeSourceAdminController {

    private final KnowledgeSourceService service;

    public KnowledgeSourceAdminController(KnowledgeSourceService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<KnowledgeSourceResponse> list(@PathVariable Long tenantId) {
        return service.list(tenantId);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<KnowledgeSourceResponse> create(@PathVariable Long tenantId,
                                                       @Body KnowledgeSourceRequest request) {
        return HttpResponse.created(service.create(tenantId, request));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{sourceId}")
    public HttpResponse<KnowledgeSourceResponse> update(@PathVariable Long tenantId, @PathVariable Long sourceId,
                                                       @Body KnowledgeSourceRequest request) {
        return service.update(tenantId, sourceId, request).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Delete("/{sourceId}")
    public HttpResponse<?> delete(@PathVariable Long tenantId, @PathVariable Long sourceId,
                                  @Nullable @QueryValue Boolean keepDocuments) {
        return service.delete(tenantId, sourceId, Boolean.TRUE.equals(keepDocuments))
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{sourceId}/run")
    public HttpResponse<KnowledgeSourceResponse> run(@PathVariable Long tenantId, @PathVariable Long sourceId) {
        return service.run(tenantId, sourceId)
                .map(source -> HttpResponse.<KnowledgeSourceResponse>accepted().body(source))
                .orElseGet(HttpResponse::notFound);
    }

    @Post("/{sourceId}/cancel")
    public HttpResponse<?> cancel(@PathVariable Long tenantId, @PathVariable Long sourceId) {
        return service.cancel(tenantId, sourceId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{sourceId}/files")
    public HttpResponse<List<SourceFileResponse>> files(@PathVariable Long tenantId, @PathVariable Long sourceId) {
        return service.filesWithoutDocument(tenantId, sourceId).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }
}
