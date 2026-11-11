package de.alpdesk.security;

/**
 * The kinds of callers (docs/architecture/tenancy.md "Who does what"). Every controller is secured with exactly one of
 * them, matching its path - never with {@code IS_AUTHENTICATED}, which would let a tenant into the admin API and vice
 * versa.
 */
public final class Roles {

    /** The operator, logged in through the frontend with the configured admin credentials (JWT). */
    public static final String ADMIN = "ROLE_ADMIN";

    /** A tenant's client software, authenticated with one of the tenant's API keys. */
    public static final String TENANT = "ROLE_TENANT";

    /** A tenant's employee, logged in through the frontend with a user the admin created (JWT). */
    public static final String TENANT_USER = "ROLE_TENANT_USER";
    /**
     * A tenant user who keeps the tenant's settings (/tenant/admin/**): users, API keys, embedding model, company
     * knowledge, usage. Has {@link #TENANT_USER} too - a tenant admin chats like every user.
     */
    public static final String TENANT_ADMIN = "ROLE_TENANT_ADMIN";

    /**
     * An MCP client asking the tenant's assistant (/mcp, docs/architecture/mcp.md) with the tenant's MCP key - like a
     * user who sees no documents. Reaches nothing but /mcp and, with its A2A door, /a2a.
     */
    public static final String MCP = "ROLE_MCP";

    /** Authentication attribute holding the authenticated tenant's id (a {@link Long}). */
    public static final String TENANT_ID_ATTRIBUTE = "tenantId";

    /** Authentication attribute holding a tenant user's id (a {@link Long}). */
    public static final String USER_ID_ATTRIBUTE = "userId";

    /** Authentication attribute holding the id of the client software's API key (a {@link Long}) - its areas. */
    public static final String API_KEY_ID_ATTRIBUTE = "apiKeyId";

    /** Authentication attribute holding the id of the tenant's MCP key a {@link #MCP} caller presented - its areas and web search. */
    public static final String MCP_KEY_ID_ATTRIBUTE = "mcpKeyId";

    /** Authentication attribute of a client software's API key: false when its questions are not kept. */
    public static final String KEEPS_HISTORY_ATTRIBUTE = "keepsHistory";

    private Roles() {
    }
}
