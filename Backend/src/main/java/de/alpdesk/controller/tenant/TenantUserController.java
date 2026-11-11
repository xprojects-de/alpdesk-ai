package de.alpdesk.controller.tenant;

import de.alpdesk.dto.tenant.TenantUserRequest;
import de.alpdesk.dto.tenant.UserFromTenantRequest;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.dto.tenant.TenantUserResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.services.tenant.TenantService;
import de.alpdesk.services.tenant.TenantUserService;
import de.alpdesk.services.tenant.UserGroupService;
import de.alpdesk.entities.tenant.TenantUser;
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
import java.util.Map;

/** A tenant's users (its employees in the frontend), managed by the admin - the tenant's admins: TenantAdminUserController. */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}/users")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantUserController {

    private final TenantUserService userService;
    private final TenantService tenantService;
    private final UserGroupService groups;

    public TenantUserController(TenantUserService userService, TenantService tenantService, UserGroupService groups) {
        this.userService = userService;
        this.tenantService = tenantService;
        this.groups = groups;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public HttpResponse<List<TenantUserResponse>> list(@PathVariable Long tenantId) {
        if (tenantService.findById(tenantId).isEmpty()) {
            return HttpResponse.notFound();
        }
        Map<Long, List<Long>> groupsOfUsers = groups.groupsOfUsers(tenantId);
        Map<Long, List<String>> wamGroupsOfUsers = groups.wamGroupsOfUsers(tenantId);
        return HttpResponse.ok(userService.findByTenant(tenantId).stream()
                .map(user -> TenantUserResponse.from(user, groupsOfUsers.getOrDefault(user.id(), List.of()),
                        wamGroupsOfUsers.getOrDefault(user.id(), List.of()))).toList());
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<?> create(@PathVariable Long tenantId, @Body TenantUserRequest request) {
        if (tenantService.findById(tenantId).isEmpty()) {
            return HttpResponse.notFound();
        }
        TenantUser user = userService.create(tenantId, request, null);
        return HttpResponse.created(response(user));
    }

    /**
     * A person's user of another tenant added to this one (docs/architecture/tenancy.md "Who does what") - the admin
     * only: a tenant admin must not see another tenant's users. 404 for an unknown tenant or user, 409 when the name
     * is taken here.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/from-tenant")
    public HttpResponse<?> addFromOtherTenant(@PathVariable Long tenantId, @Body UserFromTenantRequest request) {
        if (tenantService.findById(tenantId).isEmpty()) {
            return HttpResponse.notFound();
        }
        if (request.userId() == null) {
            throw ServiceException.badRequest("user.sourceUserRequired", "The user to add is required");
        }
        return userService.addFromOtherTenant(tenantId, request.userId(), request.active() == null || request.active(),
                        Boolean.TRUE.equals(request.admin()))
                .<HttpResponse<?>>map(user -> HttpResponse.created(response(user)))
                .orElseThrow(() -> ServiceException.notFound("user.notFound", "User not found"));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{userId}")
    public HttpResponse<?> update(@PathVariable Long tenantId, @PathVariable Long userId, @Body TenantUserRequest request) {
        return userService.update(tenantId, userId, request, null)
                .<HttpResponse<?>>map(user -> HttpResponse.ok(response(user)))
                .orElseGet(HttpResponse::notFound);
    }

    @Delete("/{userId}")
    public HttpResponse<?> delete(@PathVariable Long tenantId, @PathVariable Long userId) {
        return userService.delete(tenantId, userId, null) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    private TenantUserResponse response(TenantUser user) {
        return TenantUserResponse.from(user, groups.groupsOfUser(user.id()), groups.wamGroupsOfUser(user.id()));
    }
}
