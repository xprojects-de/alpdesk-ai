import {ROLE_ADMIN, ROLE_TENANT_USER} from "../models/auth.model";

/** The area a login belongs to: the admin's dashboard or a tenant user's app; null for a login with neither role. */
export function homePathFor(roles: string[]): string | null {
    if (roles.includes(ROLE_ADMIN)) {
        return "/dashboard";
    }
    if (roles.includes(ROLE_TENANT_USER)) {
        return "/app";
    }
    return null;
}
