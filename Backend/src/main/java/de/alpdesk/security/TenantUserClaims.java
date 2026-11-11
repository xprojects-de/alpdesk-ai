package de.alpdesk.security;

import de.alpdesk.entities.tenant.TenantUser;
import io.micronaut.security.authentication.Authentication;

import java.util.List;
import java.util.Map;

/**
 * What a tenant user's JWT carries, whether it signed in with its password or through the WAM proxy: its username,
 * {@link Roles#TENANT_USER} (a tenant admin also {@link Roles#TENANT_ADMIN}), its tenant and user id.
 */
public final class TenantUserClaims {

    private TenantUserClaims() {
    }

    public static Authentication of(TenantUser user) {
        return Authentication.build(user.username(), roles(user), attributes(user));
    }

    static List<String> roles(TenantUser user) {
        return user.admin() ? List.of(Roles.TENANT_USER, Roles.TENANT_ADMIN) : List.of(Roles.TENANT_USER);
    }

    static Map<String, Object> attributes(TenantUser user) {
        return Map.of(Roles.TENANT_ID_ATTRIBUTE, user.tenantId(), Roles.USER_ID_ATTRIBUTE, user.id());
    }
}
