import {McpAccessLevel} from "./mcp.model";

/**
 * A group of a tenant's users ("Vertrieb", "Werkstatt"): its members get its level for each connected software (set
 * in the group's dialog or on the software's access list) and find its knowledge areas. A user in no group has the chat and the company
 * knowledge. With the WAM proxy's IDM groups checked, a group with a WAM group counts for a member only while the proxy
 * lists that IDM group. With webSearch its members may search the web in the chat.
 */
export interface UserGroup {
    id: number;
    name: string;
    wamGroup: string | null;
    userIds: number[];
    areaIds: number[];
    webSearch: boolean;
    /** Its level for each connected software it may use - a connection without one: no access. */
    levels: UserGroupLevel[];
    createdAt: string;
}

export interface UserGroupLevel {
    connectionId: number;
    level: McpAccessLevel;
}

/**
 * wamGroup: empty - none; left out - none on create, unchanged on update. userIds, areaIds, webSearch and levels alike;
 * levels given replace the group's (a connection left out: no access).
 */
export interface UserGroupRequest {
    name: string;
    wamGroup?: string | null;
    userIds?: number[];
    areaIds?: number[];
    webSearch?: boolean;
    levels?: UserGroupLevel[];
}
