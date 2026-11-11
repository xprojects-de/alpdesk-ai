import {TenantUser} from "../../models/tenant-user.model";
import {UserGroup} from "../../models/user-group.model";
import * as GroupActions from "./user-group.actions";
import * as TenantUserActions from "../tenant-user/tenant-user.actions";
import * as AreaActions from "../knowledge-area/knowledge-area.actions";
import {initialState, userGroupReducer} from "./user-group.reducer";
import {initialState as usersInitialState, tenantUserReducer} from "../tenant-user/tenant-user.reducer";

describe("userGroupReducer", () => {
    const group = (id: number, name: string, userIds: number[] = [], areaIds: number[] = []): UserGroup => ({
        id,
        name,
        wamGroup: null,
        userIds,
        areaIds,
        webSearch: false,
        levels: [],
        createdAt: "2026-01-01T00:00:00Z",
    });
    const user = (id: number, groupIds: number[]): TenantUser => ({
        id,
        username: "u" + id,
        displayName: "U",
        active: true,
        admin: false,
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z",
        lastLoginAt: null,
        groupIds,
        wamGroups: [],
        language: "en",
    });

    function loaded(groups: UserGroup[]) {
        const state = userGroupReducer(initialState, GroupActions.loadGroups({scope: 1}));
        return userGroupReducer(state, GroupActions.loadGroupsSuccess({scope: 1, groups}));
    }

    it("keeps the groups by name and ignores the results for another tenant", () => {
        let state = loaded([group(1, "Werkstatt")]);
        state = userGroupReducer(state, GroupActions.createGroupSuccess({scope: 1, group: group(2, "Vertrieb")}));
        expect(state.groups.map(g => g.name)).toEqual(["Vertrieb", "Werkstatt"]);

        expect(userGroupReducer(state, GroupActions.createGroupSuccess({scope: 2, group: group(3, "Fremd")}))).toBe(
            state,
        );
        expect(userGroupReducer(state, GroupActions.loadGroups({scope: 2})).groups).toEqual([]);
    });

    it("an updated group takes its place, a deleted one leaves", () => {
        let state = loaded([group(1, "Werkstatt"), group(2, "Vertrieb")]);
        state = userGroupReducer(state, GroupActions.updateGroupSuccess({scope: 1, group: group(1, "Arbeit", [4])}));
        expect(state.groups.map(g => [g.name, g.userIds])).toEqual([
            ["Arbeit", [4]],
            ["Vertrieb", []],
        ]);

        state = userGroupReducer(state, GroupActions.deleteGroupSuccess({scope: 1, id: 2}));
        expect(state.groups.map(g => g.id)).toEqual([1]);
    });

    it("a saved or deleted user and a deleted area change the groups shown", () => {
        let state = loaded([group(1, "Vertrieb", [7], [3, 4]), group(2, "Werkstatt")]);

        state = userGroupReducer(state, TenantUserActions.updateUserSuccess({scope: 1, user: user(7, [2])}));
        expect(state.groups.map(g => g.userIds)).toEqual([[], [7]]);

        state = userGroupReducer(state, TenantUserActions.deleteUserSuccess({scope: 1, userId: 7}));
        expect(state.groups.map(g => g.userIds)).toEqual([[], []]);

        state = userGroupReducer(state, AreaActions.deleteAreaSuccess({scope: 1, id: 3}));
        expect(state.groups[0].areaIds).toEqual([4]);
    });

    it("a saved or deleted group changes the users' groups shown", () => {
        let users = tenantUserReducer(usersInitialState, TenantUserActions.loadUsers({scope: 1}));
        users = tenantUserReducer(
            users,
            TenantUserActions.loadUsersSuccess({scope: 1, users: [user(7, []), user(8, [1])]}),
        );

        users = tenantUserReducer(users, GroupActions.updateGroupSuccess({scope: 1, group: group(1, "Vertrieb", [7])}));
        expect(users.users.map(u => u.groupIds)).toEqual([[1], []]);

        users = tenantUserReducer(users, GroupActions.deleteGroupSuccess({scope: 1, id: 1}));
        expect(users.users.map(u => u.groupIds)).toEqual([[], []]);
    });
});
