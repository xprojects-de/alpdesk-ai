package de.alpdesk.controller.tenant;

import de.alpdesk.dto.tenant.UserGroupRequest;
import de.alpdesk.dto.tenant.UserGroupResponse;
import de.alpdesk.security.Roles;
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
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** A tenant's user groups, kept by the admin - like its tenant admins do (TenantAdminUserGroupController). */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}/groups")
@ExecuteOn(TaskExecutors.BLOCKING)
public class UserGroupAdminController {

    private final UserGroupService groups;

    public UserGroupAdminController(UserGroupService groups) {
        this.groups = groups;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<UserGroupResponse> list(@PathVariable Long tenantId) {
        return groups.findAll(tenantId);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<UserGroupResponse> create(@PathVariable Long tenantId, @Body UserGroupRequest request) {
        return HttpResponse.created(groups.create(tenantId, request, false));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{groupId}")
    public HttpResponse<UserGroupResponse> update(@PathVariable Long tenantId, @PathVariable Long groupId,
                                                  @Body UserGroupRequest request) {
        return groups.update(tenantId, groupId, request, false).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Delete("/{groupId}")
    public HttpResponse<?> delete(@PathVariable Long tenantId, @PathVariable Long groupId) {
        return groups.delete(tenantId, groupId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
