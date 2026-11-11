import {TenantUser} from "../models/tenant-user.model";
import {MCP_ACCESS_LABELS, McpAccessLevel} from "../models/mcp.model";
import {UserGroup, UserGroupLevel, UserGroupRequest} from "../models/user-group.model";

/** As the backend: a group's name at most 100 characters, its WAM group at most 500. */
export const GROUP_NAME_MAX = 100;
export const WAM_GROUP_MAX = 500;

/** Names as shown: a translation key and its params - the template translates it. */
export interface GroupText {
    key: string;
    params: Record<string, string>;
}

/** The groups by name, as people read them (the browser's collation). */
export function sortGroups(groups: UserGroup[]): UserGroup[] {
    return [...groups].sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * The names of these groups, by name - "no group" without one, and for a user who is no tenant admin "no group - no
 * access": access needs a group.
 */
export function groupNames(groupIds: number[], groups: UserGroup[], tenantAdmin = true): GroupText {
    const names = sortGroups(groups.filter(group => groupIds.includes(group.id))).map(group => group.name);
    if (names.length === 0) {
        return {key: tenantAdmin ? "groups.noGroup" : "groups.noGroupNoAccess", params: {}};
    }
    return {key: "groups.names", params: {names: names.join(", ")}};
}

/** The tenant's group "Standard" (no WAM group), preselected for a new user - without a group it would not get in. */
export function defaultGroupIds(groups: UserGroup[]): number[] {
    const standard = groups.find(group => group.name.toLowerCase() === "standard" && !group.wamGroup);
    return standard ? [standard.id] : [];
}

/** The members' names, by name - "no members" without one. */
export function memberNames(userIds: number[], users: TenantUser[]): GroupText {
    const names = users
        .filter(user => userIds.includes(user.id))
        .map(user => user.displayName)
        .sort((a, b) => a.localeCompare(b));
    return names.length === 0
        ? {key: "groups.noMembers", params: {}}
        : {key: "groups.names", params: {names: names.join(", ")}};
}

/** A connection's level in the group dialog: "NONE" - no access. */
export type LevelChoice = McpAccessLevel | "NONE";

export interface UserGroupFormValue {
    name: string;
    wamGroup: string;
    userIds: number[];
    areaIds: number[];
    webSearch: boolean;
    /** The level chosen per connection, by the connection's id; empty when the tenant has no software. */
    levels: Record<string, LevelChoice>;
}

/**
 * The request of the group dialog: trimmed. The WAM group is always sent - empty removes it; left out, the backend
 * would keep the old one. A group with a WAM group takes its members from the IDM: it sends none (the backend refuses
 * them). The levels go along only when the dialog showed software: a tenant without a connection sends none, so
 * nothing is changed.
 */
export function buildUserGroupRequest(value: UserGroupFormValue): UserGroupRequest {
    const choices = Object.entries(value.levels);
    const wamGroup = value.wamGroup.trim();
    return {
        name: value.name.trim(),
        wamGroup,
        userIds: wamGroup ? [] : value.userIds,
        areaIds: value.areaIds,
        webSearch: value.webSearch,
        ...(choices.length > 0
            ? {
                  levels: choices
                      .filter(([, level]) => level !== "NONE")
                      .map(([connectionId, level]) => ({
                          connectionId: Number(connectionId),
                          level: level as McpAccessLevel,
                      })),
              }
            : {}),
    };
}

/** The level of each connection for the dialog's form - "NONE" where the group has none. */
export function levelChoices(levels: UserGroupLevel[], connections: {id: number}[]): Record<string, LevelChoice> {
    const choices: Record<string, LevelChoice> = {};
    for (const connection of connections) {
        choices[String(connection.id)] = levels.find(level => level.connectionId === connection.id)?.level ?? "NONE";
    }
    return choices;
}

/** The group's software as the table shows it: each connection's name and the key of its level, in the tenant's order. */
export function groupSoftware(
    levels: UserGroupLevel[],
    connections: {id: number; name: string}[],
): {name: string; levelKey: string}[] {
    const shown: {name: string; levelKey: string}[] = [];
    for (const connection of connections) {
        const level = levels.find(item => item.connectionId === connection.id)?.level;
        if (level) {
            shown.push({name: connection.name, levelKey: MCP_ACCESS_LABELS[level]});
        }
    }
    return shown;
}

/** The users with their groups as this saved group's members say. */
export function withMembersOf(users: TenantUser[], group: UserGroup): TenantUser[] {
    return users.map(user => {
        const member = group.userIds.includes(user.id);
        const listed = user.groupIds.includes(group.id);
        if (member === listed) {
            return user;
        }
        return {...user, groupIds: member ? [...user.groupIds, group.id] : user.groupIds.filter(id => id !== group.id)};
    });
}

/** The users without this deleted group. */
export function withoutGroup(users: TenantUser[], groupId: number): TenantUser[] {
    return users.map(user =>
        user.groupIds.includes(groupId) ? {...user, groupIds: user.groupIds.filter(id => id !== groupId)} : user,
    );
}

/** The groups with their members as this saved user's groups say. */
export function withGroupsOf(groups: UserGroup[], user: TenantUser): UserGroup[] {
    return groups.map(group => {
        const member = user.groupIds.includes(group.id);
        const listed = group.userIds.includes(user.id);
        if (member === listed) {
            return group;
        }
        return {...group, userIds: member ? [...group.userIds, user.id] : group.userIds.filter(id => id !== user.id)};
    });
}

/** The groups without this deleted user. */
export function withoutMember(groups: UserGroup[], userId: number): UserGroup[] {
    return groups.map(group =>
        group.userIds.includes(userId) ? {...group, userIds: group.userIds.filter(id => id !== userId)} : group,
    );
}

/** A user whose membership comes from the IDM: it is in a group with a WAM group - the users' list says so. */
export function fromIdm(user: TenantUser, groups: UserGroup[]): boolean {
    return groups.some(group => !!group.wamGroup && user.groupIds.includes(group.id));
}

/** Setting a WAM group on a group with assigned members replaces them by the IDM's - the dialog says so before saving. */
export function replacesMembers(original: UserGroup | null, wamGroup: string): boolean {
    return !!original && !original.wamGroup && original.userIds.length > 0 && wamGroup.trim().length > 0;
}
