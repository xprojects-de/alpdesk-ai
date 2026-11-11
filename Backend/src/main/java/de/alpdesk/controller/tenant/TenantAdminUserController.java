package de.alpdesk.controller.tenant;

import de.alpdesk.dto.tenant.TenantUserRequest;
import de.alpdesk.dto.tenant.TenantUserResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
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
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;
import java.util.Map;

/** The users of the tenant admin's own tenant - created, changed and deleted like the admin does (TenantUserController). */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/users")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantAdminUserController {

    private final TenantUserService userService;
    private final UserGroupService groups;

    public TenantAdminUserController(TenantUserService userService, UserGroupService groups) {
        this.userService = userService;
        this.groups = groups;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<TenantUserResponse> list(Authentication authentication) {
        Long tenantId = TenantContext.tenantId(authentication);
        Map<Long, List<Long>> groupsOfUsers = groups.groupsOfUsers(tenantId);
        Map<Long, List<String>> wamGroupsOfUsers = groups.wamGroupsOfUsers(tenantId);
        return userService.findByTenant(tenantId).stream()
                .map(user -> TenantUserResponse.from(user, groupsOfUsers.getOrDefault(user.id(), List.of()),
                        wamGroupsOfUsers.getOrDefault(user.id(), List.of()))).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<TenantUserResponse> create(Authentication authentication, @Body TenantUserRequest request) {
        TenantUser user = userService.create(TenantContext.tenantId(authentication), request,
                TenantContext.userId(authentication));
        return HttpResponse.created(response(user));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{userId}")
    public HttpResponse<TenantUserResponse> update(Authentication authentication, @PathVariable Long userId,
                                                   @Body TenantUserRequest request) {
        return userService.update(TenantContext.tenantId(authentication), userId, request, TenantContext.userId(authentication))
                .map(user -> HttpResponse.ok(response(user)))
                .orElseGet(HttpResponse::notFound);
    }

    @Delete("/{userId}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long userId) {
        return userService.delete(TenantContext.tenantId(authentication), userId, TenantContext.userId(authentication))
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    private TenantUserResponse response(TenantUser user) {
        return TenantUserResponse.from(user, groups.groupsOfUser(user.id()), groups.wamGroupsOfUser(user.id()));
    }
}
