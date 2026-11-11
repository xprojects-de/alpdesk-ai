/** Someone the WAM proxy let through who waits for the admin (docs/architecture/tenancy.md). */
export interface AccessRequest {
    id: number;
    username: string;
    displayName: string;
    requestedAt: string;
    /** The language of the browser the person signed in with - null when it sent none. */
    language: string | null;
}

/**
 * The tenant a request goes to; active: approved at once, admin: a tenant admin (e.g. the tenant's first user),
 * language: the user's frontend language (left out: English).
 */
export interface AccessAssignment {
    tenantId: number;
    active: boolean;
    admin: boolean;
    language?: string;
}
