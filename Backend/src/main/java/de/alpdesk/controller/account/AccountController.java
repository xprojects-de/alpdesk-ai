package de.alpdesk.controller.account;

import de.alpdesk.dto.account.AccountResponse;
import de.alpdesk.dto.account.LanguageRequest;
import de.alpdesk.dto.account.PasswordChangeRequest;
import de.alpdesk.dto.account.SwitchTenantRequest;
import de.alpdesk.dto.login.TenantChoice;
import de.alpdesk.dto.tenant.TenantUserResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.mcp.McpKeyService;
import de.alpdesk.services.tenant.TenantService;
import de.alpdesk.services.tenant.TenantUserService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.token.bearer.AccessRefreshTokenLoginHandler;
import de.alpdesk.security.TenantUserClaims;
import de.alpdesk.security.WamProxy;
import de.alpdesk.services.mcp.McpConnectionService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The logged-in tenant user's own account: who it is, its password, the language of its frontend - and the switch to
 * its user of the same username in another tenant (docs/architecture/tenancy.md "Who does what").
 */
@Secured(Roles.TENANT_USER)
@Tag(name = "Users")
@SecurityRequirement(name = "UserAuth")
@Controller("/tenant/account")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AccountController {

    private final TenantUserService userService;
    private final TenantService tenantService;
    private final McpKeyService mcpKeys;
    private final AccessRefreshTokenLoginHandler tokens;
    private final WamProxy wam;
    private final McpConnectionService connections;

    public AccountController(TenantUserService userService, TenantService tenantService, McpKeyService mcpKeys,
                             AccessRefreshTokenLoginHandler tokens, WamProxy wam, McpConnectionService connections) {
        this.connections = connections;
        this.wam = wam;
        this.userService = userService;
        this.tenantService = tenantService;
        this.mcpKeys = mcpKeys;
        this.tokens = tokens;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public AccountResponse get(Authentication authentication) {
        Long tenantId = TenantContext.tenantId(authentication);
        var user = userService.findById(tenantId, TenantContext.userId(authentication))
                .orElseThrow(() -> ServiceException.notFound("user.notFound", "User not found"));
        var tenant = tenantService.findById(tenantId).orElseThrow(() -> ServiceException.notFound("tenant.notFound",
                "Tenant not found"));
        return new AccountResponse(user.id(), user.username(), user.displayName(), tenant.id(), tenant.name(),
                user.admin(), TenantUserResponse.languageOf(user), mcpKeys.hasKey(tenantId),
                tenant.tasksAllowed(), connections.answeringAgent(tenantId).isPresent(),
                userService.switchableAccounts(user).stream()
                        .map(account -> TenantChoice.from(account, wam.enabled())).toList());
    }

    /**
     * A new token for the user of the same username in another tenant - the same answer as a login. 404 for a tenant
     * where there is no such user who may log in; without the WAM proxy 400 without the target's password or with a
     * wrong one.
     */
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/switch")
    public HttpResponse<?> switchTenant(HttpRequest<?> request, Authentication authentication,
                                        @Body SwitchTenantRequest body) {
        var target = userService.switchTenant(TenantContext.tenantId(authentication),
                TenantContext.userId(authentication), body.tenantId(), body.password());
        return tokens.loginSuccess(TenantUserClaims.of(target), request);
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/password")
    public HttpResponse<?> changePassword(Authentication authentication, @Body PasswordChangeRequest request) {
        userService.changePassword(TenantContext.tenantId(authentication), TenantContext.userId(authentication),
                request.currentPassword(), request.newPassword());
        return HttpResponse.noContent();
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/language")
    public HttpResponse<?> changeLanguage(Authentication authentication, @Body LanguageRequest request) {
        userService.changeLanguage(TenantContext.tenantId(authentication), TenantContext.userId(authentication),
                request.language());
        return HttpResponse.noContent();
    }
}
