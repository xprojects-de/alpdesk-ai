package de.alpdesk.controller.tenant;

import de.alpdesk.dto.tenant.UserGroupRequest;
import de.alpdesk.dto.tenant.UserGroupResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.tenant.UserGroupService;
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

/** The user groups of the tenant admin's own tenant - kept like the admin does (UserGroupAdminController). */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/groups")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantAdminUserGroupController {

    private final UserGroupService groups;

    public TenantAdminUserGroupController(UserGroupService groups) {
        this.groups = groups;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<UserGroupResponse> list(Authentication authentication) {
        return groups.findAll(TenantContext.tenantId(authentication));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<UserGroupResponse> create(Authentication authentication, @Body UserGroupRequest request) {
        return HttpResponse.created(groups.create(TenantContext.tenantId(authentication), request, true));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{groupId}")
    public HttpResponse<UserGroupResponse> update(Authentication authentication, @PathVariable Long groupId,
                                                  @Body UserGroupRequest request) {
        return groups.update(TenantContext.tenantId(authentication), groupId, request, true)
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Delete("/{groupId}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long groupId) {
        return groups.delete(TenantContext.tenantId(authentication), groupId)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
