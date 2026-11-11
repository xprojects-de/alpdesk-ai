package de.alpdesk.controller.login;

import de.alpdesk.dto.login.TenantChoice;
import de.alpdesk.dto.login.WamChoiceResponse;
import de.alpdesk.dto.login.WamSignInRequest;
import de.alpdesk.dto.login.WamStatusResponse;
import de.alpdesk.security.TenantUserClaims;
import de.alpdesk.security.WamProxy;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.AccessRequestService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.token.bearer.AccessRefreshTokenLoginHandler;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;

/**
 * Sign-in through the WAM proxy (security.WamProxy): the frontend asks whether it is on and then signs the person in
 * with the user the proxy sends - the same token as {@code POST /login}. Anonymous, like /login.
 */
@Secured(SecurityRule.IS_ANONYMOUS)
@Tag(name = "Users - sign-in")
@Controller("/login/wam")
@ExecuteOn(TaskExecutors.BLOCKING)
public class WamLoginController {

    private final WamProxy wam;
    private final AccessRequestService accessRequests;
    private final AccessRefreshTokenLoginHandler tokens;

    public WamLoginController(WamProxy wam, AccessRequestService accessRequests, AccessRefreshTokenLoginHandler tokens) {
        this.wam = wam;
        this.accessRequests = accessRequests;
        this.tokens = tokens;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public WamStatusResponse status() {
        return new WamStatusResponse(wam.enabled(), wam.checksGroups());
    }

    /**
     * The token - or, for a person with a user in several tenants, 200 with the tenants to choose from and no token
     * (signed in by the next call with the chosen tenantId; 404 for a tenant where it has no user who may log in).
     * 404 while WAM is off, 400 without the proxy's header or with a user name that cannot be a username, 409 while
     * the person waits for approval (never 401/403 - the frontend would take it for a lost session).
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<?> signIn(HttpRequest<?> request, @Nullable @Body WamSignInRequest body) {
        if (!wam.enabled()) {
            throw ServiceException.notFound("wam.notEnabled", "Sign-in through a WAM proxy is not enabled");
        }
        String user = wam.user(request);
        if (user == null) {
            throw ServiceException.badRequest("wam.noUser", "The WAM proxy sent no user (header " + wam.userHeader()
                    + ")",
                    Map.of("header", wam.userHeader()));
        }
        var signIn = accessRequests.signIn(user, wam.displayName(request), body == null ? null : body.tenantId(),
                wam.checksGroups() ? wam.groups(request) : List.of(), body == null ? null : body.language());
        if (signIn.waits()) {
            throw ServiceException.conflict("wam.waitingForApproval", "Your access is waiting for approval");
        }
        if (signIn.user() == null) {
            return HttpResponse.ok(new WamChoiceResponse(signIn.choice().stream().map(account -> TenantChoice.from(account, true)).toList()));
        }
        return tokens.loginSuccess(TenantUserClaims.of(signIn.user()), request);
    }
}
