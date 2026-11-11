import {TenantUser} from "../models/tenant-user.model";
import {UserGroup} from "../models/user-group.model";
import {
    buildUserGroupRequest,
    fromIdm,
    defaultGroupIds,
    groupNames,
    groupSoftware,
    levelChoices,
    memberNames,
    replacesMembers,
    withGroupsOf,
    withMembersOf,
    withoutGroup,
    withoutMember,
} from "./user-group.util";

const user = (id: number, displayName: string, groupIds: number[] = []): TenantUser => ({
    id,
    username: "u" + id,
    displayName,
    active: true,
    admin: false,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    lastLoginAt: null,
    groupIds,
    wamGroups: [],
    language: "en",
});

const group = (id: number, name: string, userIds: number[] = []): UserGroup => ({
    id,
    name,
    wamGroup: null,
    userIds,
    areaIds: [],
    webSearch: false,
    levels: [],
    createdAt: "2026-01-01T00:00:00Z",
});

describe("groupNames and memberNames", () => {
    it("names the groups and the members by name, or says there are none", () => {
        const groups = [group(1, "Werkstatt"), group(2, "Vertrieb"), group(3, "Einkauf")];

        expect(groupNames([1, 2], groups)).toEqual({key: "groups.names", params: {names: "Vertrieb, Werkstatt"}});
        expect(groupNames([], groups)).toEqual({key: "groups.noGroup", params: {}});
        expect(groupNames([], groups, false)).toEqual({key: "groups.noGroupNoAccess", params: {}});
        expect(memberNames([2, 1], [user(1, "Max"), user(2, "Anna"), user(3, "Otto")])).toEqual({
            key: "groups.names",
            params: {names: "Anna, Max"},
        });
        expect(memberNames([], [user(1, "Max")])).toEqual({key: "groups.noMembers", params: {}});
    });
});

describe("buildUserGroupRequest", () => {
    it("trims the name and the WAM group; an empty WAM group is sent empty, so it is removed", () => {
        expect(
            buildUserGroupRequest({
                name: " Vertrieb ",
                wamGroup: " Gruppe1 ",
                userIds: [1],
                areaIds: [2],
                webSearch: true,
                levels: {},
            }),
        ).toEqual({
            name: "Vertrieb",
            wamGroup: "Gruppe1",
            userIds: [],
            areaIds: [2],
            webSearch: true,
        });
        expect(
            buildUserGroupRequest({
                name: "Vertrieb",
                wamGroup: "  ",
                userIds: [],
                areaIds: [],
                webSearch: false,
                levels: {},
            }).wamGroup,
        ).toBe("");
    });

    it("sends the level of each software chosen - no access left out - and nothing for a tenant without software", () => {
        const value = {name: "Buchhaltung", wamGroup: "", userIds: [], areaIds: [], webSearch: false};
        expect(buildUserGroupRequest({...value, levels: {"3": "WRITE", "4": "NONE", "5": "READ"}}).levels).toEqual([
            {connectionId: 3, level: "WRITE"},
            {connectionId: 5, level: "READ"},
        ]);
        expect(buildUserGroupRequest({...value, levels: {"3": "NONE"}}).levels).toEqual([]);
        expect(buildUserGroupRequest({...value, levels: {}})).not.toHaveProperty("levels");
    });
});

describe("members from the IDM", () => {
    it("a group with a WAM group sends no members - the IDM gives them; one without sends its members", () => {
        const value = {name: "Vertrieb", userIds: [1, 2], areaIds: [], webSearch: false, levels: {}};
        expect(buildUserGroupRequest({...value, wamGroup: "AI-Vertrieb"}).userIds).toEqual([]);
        expect(buildUserGroupRequest({...value, wamGroup: " "}).userIds).toEqual([1, 2]);
    });

    it("marks a user in a group with a WAM group as one from the IDM", () => {
        const idm = {...group(1, "Vertrieb"), wamGroup: "AI-Vertrieb"};
        const plain = group(2, "Werkstatt");
        expect(fromIdm(user(1, "Anna", [1]), [idm, plain])).toBe(true);
        expect(fromIdm(user(2, "Bernd", [2]), [idm, plain])).toBe(false);
    });

    it("warns before a WAM group replaces assigned members - not for a new group or one that had a WAM group", () => {
        const assigned = group(1, "Vertrieb", [5]);
        expect(replacesMembers(assigned, "AI-Vertrieb")).toBe(true);
        expect(replacesMembers(assigned, "  ")).toBe(false);
        expect(replacesMembers(null, "AI-Vertrieb")).toBe(false);
        expect(replacesMembers({...assigned, wamGroup: "Alt"}, "AI-Vertrieb")).toBe(false);
        expect(replacesMembers(group(2, "Leer"), "AI-Vertrieb")).toBe(false);
    });
});

describe("a group's software", () => {
    const connections = [
        {id: 3, name: "Buchhaltung"},
        {id: 4, name: "Lager"},
    ];

    it("gives the form every connection's level, no access where the group has none", () => {
        expect(levelChoices([{connectionId: 3, level: "WRITE"}], connections)).toEqual({"3": "WRITE", "4": "NONE"});
    });

    it("shows the software the group may use with its level, in the tenant's order", () => {
        expect(
            groupSoftware(
                [
                    {connectionId: 4, level: "READ"},
                    {connectionId: 3, level: "WRITE"},
                    {connectionId: 99, level: "READ"},
                ],
                connections,
            ),
        ).toEqual([
            {name: "Buchhaltung", levelKey: "mcpConnections.level.write"},
            {name: "Lager", levelKey: "mcpConnections.level.read"},
        ]);
    });
});

describe("membership kept alike in the users and the groups", () => {
    it("a saved group adds itself to its members and leaves those it no longer has", () => {
        const users = [user(1, "Anna", [5]), user(2, "Max"), user(3, "Otto", [6])];

        const updated = withMembersOf(users, group(5, "Vertrieb", [2]));

        expect(updated.map(u => u.groupIds)).toEqual([[], [5], [6]]);
        expect(updated[2]).toBe(users[2]);
    });

    it("a deleted group leaves every user", () => {
        expect(withoutGroup([user(1, "Anna", [5, 6])], 5)[0].groupIds).toEqual([6]);
    });

    it("a saved user joins its groups and leaves the others; a deleted one leaves all", () => {
        const groups = [group(5, "Vertrieb", [1]), group(6, "Werkstatt"), group(7, "Einkauf", [2])];

        expect(withGroupsOf(groups, user(1, "Anna", [6])).map(g => g.userIds)).toEqual([[], [1], [2]]);
        expect(withoutMember(groups, 2).map(g => g.userIds)).toEqual([[1], [], []]);
    });
});

describe("defaultGroupIds", () => {
    const group = (id: number, name: string, wamGroup: string | null = null) =>
        ({
            id,
            name,
            wamGroup,
            userIds: [],
            areaIds: [],
            levels: [],
            webSearch: false,
            createdAt: "",
        }) as unknown as UserGroup;

    it("preselects the tenant's Standard for a new user - whatever its case, never one the IDM fills", () => {
        expect(defaultGroupIds([group(1, "Vertrieb"), group(2, "standard")])).toEqual([2]);
        expect(defaultGroupIds([group(1, "Standard", "AI-Allgemein")])).toEqual([]);
        expect(defaultGroupIds([])).toEqual([]);
    });
});
