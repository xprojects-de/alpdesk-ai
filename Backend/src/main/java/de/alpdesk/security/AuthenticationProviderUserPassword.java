package de.alpdesk.security;

import de.alpdesk.services.tenant.TenantUserService;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.AuthenticationFailureReason;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.authentication.provider.HttpRequestExecutorAuthenticationProvider;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * The one login ({@code POST /login}) for both kinds of people: the admin account configured via
 * {@code APP_USERNAME}/{@code APP_PASSWORD} (see application.properties), then the tenant users. A tenant user's JWT
 * carries its tenant and user id, a tenant admin's also the role {@link Roles#TENANT_ADMIN} ({@link TenantUserClaims}).
 * A username in several tenants signs in with the tenant's login key in the header {@value #LOGIN_KEY_HEADER}, which
 * the tenant's own login page sends (docs/architecture/branding.md).
 * With the WAM proxy on, tenant users sign in through it only - the admin keeps this login. There is deliberately no
 * lockout after failed attempts: behind the reverse proxy
 * every request arrives from the proxy's address, so a per-IP lockout would let one client lock out everyone.
 * <p>
 * An executor provider: Micronaut runs it on the blocking executor. A plain one ran on the Netty event loop - the user's
 * lookup and some hundred milliseconds of PBKDF2 per login held up every other request and the chats' streams.
 */
@Singleton
class AuthenticationProviderUserPassword<B> implements HttpRequestExecutorAuthenticationProvider<B> {

    static final String LOGIN_KEY_HEADER = "X-Login-Key";

    private final String expectedUsername;
    private final String expectedPassword;
    private final TenantUserService tenantUsers;

    AuthenticationProviderUserPassword(@Value("${app.username}") String expectedUsername,
                                       @Value("${app.password}") String expectedPassword,
                                       TenantUserService tenantUsers) {
        this.expectedUsername = expectedUsername;
        this.expectedPassword = expectedPassword;
        this.tenantUsers = tenantUsers;
    }

    @org.jspecify.annotations.NonNull
    @Override
    public AuthenticationResponse authenticate(
            @Nullable HttpRequest<B> httpRequest,
            @NonNull @org.jspecify.annotations.NonNull AuthenticationRequest<String, String> authenticationRequest
    ) {
        String username = authenticationRequest.getIdentity();
        String password = authenticationRequest.getSecret();

        if (constantTimeEquals(username, expectedUsername) && constantTimeEquals(password, expectedPassword)) {
            return AuthenticationResponse.success(username, List.of(Roles.ADMIN));
        }
        String loginKey = httpRequest == null ? null : httpRequest.getHeaders().get(LOGIN_KEY_HEADER);
        return tenantUsers.authenticate(username, password, loginKey)
                .map(user -> AuthenticationResponse.success(user.username(), TenantUserClaims.roles(user),
                        TenantUserClaims.attributes(user)))
                .orElseGet(() -> AuthenticationResponse.failure(AuthenticationFailureReason.CREDENTIALS_DO_NOT_MATCH));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
