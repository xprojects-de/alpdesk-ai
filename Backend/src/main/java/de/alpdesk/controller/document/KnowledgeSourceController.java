package de.alpdesk.controller.document;

import de.alpdesk.dto.document.KnowledgeSourceRequest;
import de.alpdesk.dto.document.KnowledgeSourceResponse;
import de.alpdesk.dto.document.SourceFileResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
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
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** The knowledge sources of the tenant admin's own tenant - like the admin's (KnowledgeSourceAdminController). */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/knowledge-sources")
@ExecuteOn(TaskExecutors.BLOCKING)
public class KnowledgeSourceController {

    private final KnowledgeSourceService service;

    public KnowledgeSourceController(KnowledgeSourceService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<KnowledgeSourceResponse> list(Authentication authentication) {
        return service.list(tenantId(authentication));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<KnowledgeSourceResponse> create(Authentication authentication,
                                                       @Body KnowledgeSourceRequest request) {
        return HttpResponse.created(service.create(tenantId(authentication), request));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{sourceId}")
    public HttpResponse<KnowledgeSourceResponse> update(Authentication authentication, @PathVariable Long sourceId,
                                                       @Body KnowledgeSourceRequest request) {
        return service.update(tenantId(authentication), sourceId, request).map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    /** keepDocuments: its documents stay as ordinary ones, no longer kept up to date. */
    @Delete("/{sourceId}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long sourceId,
                                  @Nullable @QueryValue Boolean keepDocuments) {
        return service.delete(tenantId(authentication), sourceId, Boolean.TRUE.equals(keepDocuments))
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** A run by hand: everything open is read, in the background. */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{sourceId}/run")
    public HttpResponse<KnowledgeSourceResponse> run(Authentication authentication, @PathVariable Long sourceId) {
        return service.run(tenantId(authentication), sourceId)
                .map(source -> HttpResponse.<KnowledgeSourceResponse>accepted().body(source))
                .orElseGet(HttpResponse::notFound);
    }

    @Post("/{sourceId}/cancel")
    public HttpResponse<?> cancel(Authentication authentication, @PathVariable Long sourceId) {
        return service.cancel(tenantId(authentication), sourceId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** The files without a document - refused, or deleted by hand. */
    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{sourceId}/files")
    public HttpResponse<List<SourceFileResponse>> files(Authentication authentication, @PathVariable Long sourceId) {
        return service.filesWithoutDocument(tenantId(authentication), sourceId).map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    private static Long tenantId(Authentication authentication) {
        return TenantContext.tenantId(authentication);
    }
}
