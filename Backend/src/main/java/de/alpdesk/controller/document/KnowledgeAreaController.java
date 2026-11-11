package de.alpdesk.controller.document;

import de.alpdesk.dto.document.KnowledgeAreaRequest;
import de.alpdesk.dto.document.KnowledgeAreaResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.document.KnowledgeAreaService;
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

/** The knowledge areas of the tenant admin's own tenant - like the admin's (KnowledgeAreaAdminController). */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/knowledge-areas")
@ExecuteOn(TaskExecutors.BLOCKING)
public class KnowledgeAreaController {

    private final KnowledgeAreaService areas;

    public KnowledgeAreaController(KnowledgeAreaService areas) {
        this.areas = areas;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<KnowledgeAreaResponse> list(Authentication authentication) {
        return areas.findAll(TenantContext.tenantId(authentication)).stream().map(KnowledgeAreaResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<KnowledgeAreaResponse> create(Authentication authentication, @Body KnowledgeAreaRequest request) {
        return HttpResponse.created(KnowledgeAreaResponse.from(areas.create(TenantContext.tenantId(authentication),
                request.name())));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{areaId}")
    public HttpResponse<KnowledgeAreaResponse> rename(Authentication authentication, @PathVariable Long areaId,
                                                      @Body KnowledgeAreaRequest request) {
        return areas.rename(TenantContext.tenantId(authentication), areaId, request.name())
                .map(area -> HttpResponse.ok(KnowledgeAreaResponse.from(area)))
                .orElseGet(HttpResponse::notFound);
    }

    /** 409 while documents belong to it - see KnowledgeAreaService.delete. */
    @Delete("/{areaId}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long areaId) {
        return areas.delete(TenantContext.tenantId(authentication), areaId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
