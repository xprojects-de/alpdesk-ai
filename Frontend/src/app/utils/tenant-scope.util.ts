import {TenantScope} from "../models/tenant.model";

/** The base of a tenant's settings API: the tenant admin's own, or the admin's for the tenant with this id. */
export function tenantSettingsUrl(apiUrl: string, scope: TenantScope): string {
    return scope === "self" ? `${apiUrl}/tenant/admin` : `${apiUrl}/admin/tenants/${scope}`;
}
