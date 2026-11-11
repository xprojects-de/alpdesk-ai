/**
 * The tenant a person with users in several tenants chose last in this browser (docs/architecture/tenancy.md "Sign-in
 * through a WAM proxy"): the WAM sign-in tries it first, so a second visit needs no click; the switch in the user menu
 * updates it. Per viewer - a convenience, never a right: the backend checks the person has a user there.
 */
export const LAST_TENANT_KEY = "last_tenant";

export function readLastTenant(storage: Pick<Storage, "getItem"> | null): number | undefined {
    try {
        const value = Number(storage?.getItem(LAST_TENANT_KEY));
        return Number.isInteger(value) && value > 0 ? value : undefined;
    } catch {
        return undefined;
    }
}

/** null forgets it - a tenant where the person has no user any more. */
export function storeLastTenant(
    storage: Pick<Storage, "setItem" | "removeItem"> | null,
    tenantId: number | null,
): void {
    try {
        if (tenantId) {
            storage?.setItem(LAST_TENANT_KEY, String(tenantId));
        } else {
            storage?.removeItem(LAST_TENANT_KEY);
        }
    } catch {
        // Storage disabled: the person chooses at every visit.
    }
}
