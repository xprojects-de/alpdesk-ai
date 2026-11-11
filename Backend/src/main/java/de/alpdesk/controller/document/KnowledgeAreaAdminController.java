package de.alpdesk.controller.document;

import de.alpdesk.dto.document.KnowledgeAreaRequest;
import de.alpdesk.dto.document.KnowledgeAreaResponse;
import de.alpdesk.security.Roles;
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
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** A tenant's knowledge areas, kept by the admin - like its tenant admins do (KnowledgeAreaController). */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}/knowledge-areas")
@ExecuteOn(TaskExecutors.BLOCKING)
public class KnowledgeAreaAdminController {

    private final KnowledgeAreaService areas;

    public KnowledgeAreaAdminController(KnowledgeAreaService areas) {
        this.areas = areas;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<KnowledgeAreaResponse> list(@PathVariable Long tenantId) {
        return areas.findAll(tenantId).stream().map(KnowledgeAreaResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<KnowledgeAreaResponse> create(@PathVariable Long tenantId, @Body KnowledgeAreaRequest request) {
        return HttpResponse.created(KnowledgeAreaResponse.from(areas.create(tenantId, request.name())));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{areaId}")
    public HttpResponse<KnowledgeAreaResponse> rename(@PathVariable Long tenantId, @PathVariable Long areaId,
                                                      @Body KnowledgeAreaRequest request) {
        return areas.rename(tenantId, areaId, request.name())
                .map(area -> HttpResponse.ok(KnowledgeAreaResponse.from(area)))
                .orElseGet(HttpResponse::notFound);
    }

    @Delete("/{areaId}")
    public HttpResponse<?> delete(@PathVariable Long tenantId, @PathVariable Long areaId) {
        return areas.delete(tenantId, areaId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
