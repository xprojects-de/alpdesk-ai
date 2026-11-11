package de.alpdesk.security;

import de.alpdesk.services.tenant.TenantUserService;
import de.alpdesk.services.tenant.UserGroupService;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.rules.SecurityRuleResult;
import io.micronaut.security.rules.SecuredAnnotationRule;
import io.micronaut.security.token.Claims;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * Rejects every request of a tenant user who was deactivated or deleted, or whose tenant was, or who is no tenant admin
 * and in no group that counts (TenantUserService.inAGroup) - although the JWT is
 * still valid for up to a day -, every request with a token issued before an admin set the user a new password, and
 * every request to the tenant admins' API (/tenant/admin/**) of a user who is no tenant admin any more - and, with the
 * WAM proxy on, every request on which the proxy no longer sends the token's user. With the proxy's IDM groups checked,
 * it keeps the groups each request sends on the user ({@link UserGroupService#recordWamGroups}). Runs before the role
 * rules; for every other caller it has no opinion.
 */
@Singleton
public class ActiveTenantUserRule implements SecurityRule<HttpRequest<?>> {

    private final TenantUserService tenantUsers;
    private final UserGroupService groups;
    private final WamProxy wam;
    /**
     * The tenant admins' API as the request's path has it - below the context path behind a reverse proxy under a
     * sub-path: without it a former tenant admin kept that API there until the token expired.
     */
    private final String tenantAdminPath;

    private final Scheduler blocking;

    public ActiveTenantUserRule(TenantUserService tenantUsers, UserGroupService groups, WamProxy wam,
                                @Value("${micronaut.server.context-path:}") @Nullable String contextPath,
                                @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.tenantUsers = tenantUsers;
        this.groups = groups;
        this.wam = wam;
        this.tenantAdminPath = tenantAdminPath(contextPath);
        this.blocking = Schedulers.fromExecutorService(executor);
    }

    /** "/tenant/admin/" below the context path - written with or without its slashes. */
    static String tenantAdminPath(@Nullable String contextPath) {
        String path = contextPath == null ? "" : contextPath.strip().replaceAll("/+$", "");
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return path + "/tenant/admin/";
    }

    @Override
    public Publisher<SecurityRuleResult> check(@Nullable HttpRequest<?> request, @Nullable Authentication authentication) {
        if (authentication == null || !authentication.getRoles().contains(Roles.TENANT_USER)) {
            return Mono.just(SecurityRuleResult.UNKNOWN);
        }
        if (wam.enabled() && !signedInAtTheProxy(request, authentication)) {
            return Mono.just(SecurityRuleResult.REJECTED);
        }
        // A blocking JDBC call - on the blocking executor, not Reactor's boundedElastic: the MCP server's tools run
        // there for minutes and would hold every request's sign-in.
        boolean adminRequired = request != null && request.getPath().startsWith(tenantAdminPath);
        Instant issuedAt = issuedAt(authentication);
        return Mono.fromCallable(() -> {
                    Long userId = TenantContext.userId(authentication);
                    Optional<Boolean> admin = tenantUsers.adminIfActive(TenantContext.tenantId(authentication), userId,
                            issuedAt);
                    if (admin.isEmpty()) {
                        return SecurityRuleResult.REJECTED;
                    }
                    // The IDM groups of this request first: they decide whether a group still lets the user in.
                    if (wam.checksGroups() && request != null) {
                        groups.recordWamGroups(userId, wam.groups(request));
                    }
                    if (!admin.get() && (adminRequired || !tenantUsers.inAGroup(userId))) {
                        return SecurityRuleResult.REJECTED;
                    }
                    return SecurityRuleResult.UNKNOWN;
                })
                .subscribeOn(blocking);
    }

    /**
     * Whether the proxy still sends the user the token belongs to: another person signing in at the proxy on the same
     * computer must not go on in the first one's session - the rejection logs the frontend out, which signs the new
     * one in.
     */
    private boolean signedInAtTheProxy(@Nullable HttpRequest<?> request, Authentication authentication) {
        String user = request == null ? null : wam.user(request);
        return user != null && user.equalsIgnoreCase(authentication.getName());
    }

    /** The JWT's "iat" - a token without one counts as issued at the beginning of time. */
    private static Instant issuedAt(Authentication authentication) {
        return switch (authentication.getAttributes().get(Claims.ISSUED_AT)) {
            case Date date -> date.toInstant();
            case Number seconds -> Instant.ofEpochSecond(seconds.longValue());
            case null, default -> Instant.EPOCH;
        };
    }

    @Override
    public int getOrder() {
        return SecuredAnnotationRule.ORDER - 100;
    }
}
