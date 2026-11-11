package de.alpdesk.controller.tenant;

import de.alpdesk.dto.tenant.TenantModelsRequest;
import de.alpdesk.dto.tenant.TenantModelsResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.tenant.TenantService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Hidden;

/** The models a tenant may use - assigned by the admin only. */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}/models")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantModelController {

    private final TenantModelService tenantModels;
    private final TenantService tenantService;

    public TenantModelController(TenantModelService tenantModels, TenantService tenantService) {
        this.tenantModels = tenantModels;
        this.tenantService = tenantService;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public HttpResponse<TenantModelsResponse> list(@PathVariable Long tenantId) {
        if (tenantService.findById(tenantId).isEmpty()) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(new TenantModelsResponse(tenantModels.findAssignedIds(tenantId)));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put
    public HttpResponse<TenantModelsResponse> assign(@PathVariable Long tenantId, @Body TenantModelsRequest request) {
        return tenantModels.assign(tenantId, request.modelIds())
                .map(ids -> HttpResponse.ok(new TenantModelsResponse(ids)))
                .orElseGet(HttpResponse::notFound);
    }
}
