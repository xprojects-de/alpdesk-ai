package de.alpdesk.controller.tenant;

import de.alpdesk.dto.tenant.AccessAssignRequest;
import de.alpdesk.dto.tenant.AccessRequestResponse;
import de.alpdesk.dto.tenant.TenantUserResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.AccessRequestService;
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
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** The people the WAM proxy let through who wait for the admin: assigned to a tenant, or rejected. */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/access-requests")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AccessRequestController {

    private final AccessRequestService requests;

    public AccessRequestController(AccessRequestService requests) {
        this.requests = requests;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<AccessRequestResponse> list() {
        return requests.findAll().stream().map(AccessRequestResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/{id}/assign")
    public HttpResponse<?> assign(@PathVariable Long id, @Body AccessAssignRequest request) {
        if (request.tenantId() == null) {
            throw ServiceException.badRequest("accessRequest.tenantRequired", "Tenant is required");
        }
        return requests.assign(id, request.tenantId(), Boolean.TRUE.equals(request.active()),
                        Boolean.TRUE.equals(request.admin()), request.language())
                .<HttpResponse<?>>map(user -> HttpResponse.created(TenantUserResponse.from(user, List.of(), List.of())))
                .orElseGet(HttpResponse::notFound);
    }

    @Delete("/{id}")
    public HttpResponse<?> reject(@PathVariable Long id) {
        return requests.reject(id) ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
