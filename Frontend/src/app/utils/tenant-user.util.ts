import {TenantUser, TenantUserRequest} from "../models/tenant-user.model";
import {TenantScope} from "../models/tenant.model";
import {LANGUAGES, languageOf} from "./language.util";

/**
 * Whether only the admin creates users and changes usernames: with the WAM proxy a username is a person's sign-in
 * there - new people come through their access request, which the admin assigns. The backend refuses it (409).
 */
export function usersCreatedByAdminOnly(scope: TenantScope, wamEnabled: boolean): boolean {
    return wamEnabled && scope === "self";
}

export interface TenantUserFormValue {
    username: string;
    displayName: string;
    password: string;
    active: boolean;
    admin: boolean;
    groupIds: number[];
    language: string;
}

/**
 * The request of the user dialog: an empty password on edit is left out, so the backend keeps the current one. The
 * language is always sent - the dialog presets English for a new user and the user's own on edit. On edit the groups
 * go only when they were changed in the dialog (originalGroupIds: those it was opened with): the groups panel changes
 * members too, and a dialog opened before must not put a user back into a group it was taken out of meanwhile.
 */
export function buildTenantUserRequest(
    value: TenantUserFormValue,
    originalGroupIds: number[] | null = null,
): TenantUserRequest {
    const request: TenantUserRequest = {
        username: value.username.trim(),
        displayName: value.displayName.trim(),
        active: value.active,
        admin: value.admin,
        language: value.language,
    };
    if (originalGroupIds === null || !sameIds(value.groupIds, originalGroupIds)) {
        request.groupIds = value.groupIds;
    }
    if (value.password) {
        request.password = value.password;
    }
    return request;
}

/**
 * The groups chosen in the dialog plus those of the user the dialog could not offer (created after its list was
 * loaded): a select keeps only values it has an option for, and would take the user out of a group nobody saw.
 */
export function withUnlistedGroups(chosen: number[], original: number[], listed: number[]): number[] {
    return [...chosen, ...original.filter(id => !listed.includes(id) && !chosen.includes(id))];
}

function sameIds(first: number[], second: number[]): boolean {
    return first.length === second.length && first.every(id => second.includes(id));
}

/**
 * A user's status in the list as a chip: the key of its label and its tone (app-chip classes in styles.scss). With the WAM proxy,
 * someone the admin assigned to the tenant but nobody approved yet - inactive and never signed in - waits for the
 * tenant admin.
 */
export function tenantUserStatus(user: TenantUser, wamEnabled: boolean): {label: string; tone: string} {
    if (user.active) {
        return {label: "users.status.active", tone: "ok"};
    }
    return wamEnabled && !user.lastLoginAt
        ? {label: "users.status.waiting", tone: "warn"}
        : {label: "users.status.inactive", tone: ""};
}

/**
 * A user's language as the list shows it - the one the frontend speaks for it, by its own name ("Deutsch"): "de-AT"
 * is German, a tag no longer offered and none at all (a user from before languages were stored) are English.
 */
export function languageLabel(tag: string | null): string {
    const spoken = languageOf(tag);
    return LANGUAGES.find(language => language.value === spoken)?.label ?? spoken;
}

/** The users whose username or name contains the search text, whatever the case. */
export function filterTenantUsers(users: TenantUser[], query: string): TenantUser[] {
    const text = query.trim().toLocaleLowerCase();
    return users.filter(
        user => user.username.toLocaleLowerCase().includes(text) || user.displayName.toLocaleLowerCase().includes(text),
    );
}
