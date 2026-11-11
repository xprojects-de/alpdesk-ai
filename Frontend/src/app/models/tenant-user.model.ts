/** An employee of a tenant who logs in to the tenant area (/app). */
export interface TenantUser {
    id: number;
    username: string;
    displayName: string;
    active: boolean;
    /** A tenant admin: keeps the tenant's settings (users, keys, company knowledge, usage) and chats. */
    admin: boolean;
    createdAt: string;
    updatedAt: string;
    lastLoginAt: string | null;
    /** The user's groups: its levels for the connected software and its knowledge areas come from them. */
    groupIds: number[];
    /** The IDM groups the WAM proxy sent on the user's last request, lower case - empty unless they are checked. */
    wamGroups: string[];
    /** The frontend's language tag ("en", "de"), set by the admin and changed by the user (utils/language.util.ts). */
    language: string;
}

/**
 * A person's user of another tenant the admin adds to this one (the same username, display name and language, no
 * password): active (left out: yes), a tenant admin (left out: no).
 */
export interface UserFromTenantRequest {
    userId: number;
    active: boolean;
    admin: boolean;
}

/**
 * `password` is required on create; left out on update it stays unchanged, like `active`, `admin`, `groupIds` and
 * `language` (left out on create: English).
 */
export interface TenantUserRequest {
    username: string;
    displayName: string;
    password?: string;
    active?: boolean;
    admin?: boolean;
    groupIds?: number[];
    language?: string;
}
