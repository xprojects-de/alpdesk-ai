export const ROLE_ADMIN = "ROLE_ADMIN";
export const ROLE_TENANT_USER = "ROLE_TENANT_USER";
/** A tenant user who also keeps the tenant's settings (users, keys, company knowledge, usage). */
export const ROLE_TENANT_ADMIN = "ROLE_TENANT_ADMIN";

export interface LoginRequest {
    username: string;
    password: string;
    /**
     * The tenant's own login page's key (docs/architecture/branding.md): sent as the header X-Login-Key, it picks the
     * tenant for a username that is in several.
     */
    loginKey?: string;
}

/** A tenant where the person has a user who may log in - chosen at the WAM sign-in or in the user menu's switch. */
export interface TenantChoice {
    id: number;
    name: string;
    /** Its login page's key - the choice shows its logo; null in the account without WAM (another person may share the name). */
    loginKey: string | null;
}

export interface LoginResponse {
    access_token?: string;
    token?: string;
    username?: string;
    roles?: string[];
    /** The WAM sign-in of a person with a user in several tenants: no token, the tenants to choose from. */
    tenants?: TenantChoice[];
}

export interface AuthState {
    token: string | null;
    username: string | null;
    roles: string[];
    isAuthenticated: boolean;
    loading: boolean;
    error: string | null;
    /** Tenant users sign in through a WAM proxy - no password anywhere (docs/architecture/tenancy.md). */
    wamEnabled: boolean;
    /** A user group's WAM group is checked against the IDM groups the proxy sends (app.wam.groups-header). */
    wamGroupsChecked: boolean;
    /** The WAM proxy's user is known to nobody yet, or not approved - the login page says so. */
    wamWaiting: boolean;
    /** Why the sign-in through the WAM proxy failed - apart from the password login's error. */
    wamError: string | null;
    /** The person has a user in several tenants and chooses one; null: nothing to choose. */
    wamChoice: TenantChoice[] | null;
    /** Why the switch to another tenant failed - the dialog asking the password shows it. */
    switchError: string | null;
}

export interface WamStatus {
    enabled: boolean;
    groupsChecked: boolean;
}
