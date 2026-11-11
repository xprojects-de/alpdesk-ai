package de.alpdesk.security;

import io.micronaut.security.authentication.Authentication;

/**
 * Reads the authenticated tenant (and tenant user) out of an {@link Authentication} - built by
 * {@link ApiKeyTokenValidator} for the client software, by the login for a tenant user.
 */
public final class TenantContext {

    private TenantContext() {
    }

    /**
     * The tenant id of a {@link Roles#TENANT} or {@link Roles#TENANT_USER} caller. Tenant endpoints take the tenant
     * from here and nowhere else - never from a path, query or body value.
     *
     * @throws IllegalStateException if the authentication carries no tenant (i.e. it is not a tenant caller)
     */
    public static Long tenantId(Authentication authentication) {
        return longAttribute(authentication, Roles.TENANT_ID_ATTRIBUTE);
    }

    /**
     * The user id of a {@link Roles#TENANT_USER} caller.
     *
     * @throws IllegalStateException if the authentication carries no user (i.e. it is not a tenant user)
     */
    public static Long userId(Authentication authentication) {
        return longAttribute(authentication, Roles.USER_ID_ATTRIBUTE);
    }

    /**
     * The API key of a {@link Roles#TENANT} caller - which knowledge areas the client software finds.
     *
     * @throws IllegalStateException if the authentication carries no key (i.e. it is not the client software)
     */
    public static Long apiKeyId(Authentication authentication) {
        return longAttribute(authentication, Roles.API_KEY_ID_ATTRIBUTE);
    }

    /**
     * The MCP key of a {@link Roles#MCP} caller - which knowledge areas it finds and whether it searches the web.
     *
     * @throws IllegalStateException if the authentication carries no MCP key
     */
    public static Long mcpKeyId(Authentication authentication) {
        return longAttribute(authentication, Roles.MCP_KEY_ID_ATTRIBUTE);
    }

    /** Whether the client software's key keeps its questions as conversations - true for any other caller. */
    public static boolean keepsHistory(Authentication authentication) {
        return !Boolean.FALSE.equals(authentication.getAttributes().get(Roles.KEEPS_HISTORY_ATTRIBUTE));
    }

    private static Long longAttribute(Authentication authentication, String name) {
        // A Long when just authenticated, an Integer when read back from a JWT's claims.
        if (authentication.getAttributes().get(name) instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Authentication carries no " + name);
    }
}
