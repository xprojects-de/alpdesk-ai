import {createReducer, on} from "@ngrx/store";
import {TenantScope} from "../../models/tenant.model";
import {UserGroup} from "../../models/user-group.model";
import {sortGroups, withGroupsOf, withoutMember} from "../../utils/user-group.util";
import * as GroupActions from "./user-group.actions";
import * as TenantUserActions from "../tenant-user/tenant-user.actions";
import * as AreaActions from "../knowledge-area/knowledge-area.actions";

/** One tenant's user groups at a time: the tenant admin's own, or those of the tenant the admin opened. */
export interface UserGroupState {
    scope: TenantScope | null;
    groups: UserGroup[];
    loading: boolean;
}

export const initialState: UserGroupState = {
    scope: null,
    groups: [],
    loading: false,
};

export const userGroupReducer = createReducer(
    initialState,

    on(GroupActions.loadGroups, (state, {scope}) => ({
        scope,
        groups: state.scope === scope ? state.groups : [],
        loading: true,
    })),
    // Results for another tenant (the admin opened another one's meanwhile) are ignored.
    on(GroupActions.loadGroupsSuccess, (state, {scope, groups}) =>
        state.scope === scope ? {...state, groups: sortGroups(groups), loading: false} : state,
    ),
    on(GroupActions.loadGroupsFailure, state => ({...state, loading: false})),

    on(GroupActions.createGroupSuccess, (state, {scope, group}) =>
        state.scope === scope ? {...state, groups: sortGroups([...state.groups, group])} : state,
    ),
    on(GroupActions.updateGroupSuccess, (state, {scope, group}) =>
        state.scope === scope
            ? {...state, groups: sortGroups(state.groups.map(g => (g.id === group.id ? group : g)))}
            : state,
    ),
    on(GroupActions.deleteGroupSuccess, (state, {scope, id}) =>
        state.scope === scope ? {...state, groups: state.groups.filter(g => g.id !== id)} : state,
    ),

    // A user saved with its groups, or deleted, changes the groups' members - shown without loading them again.
    on(TenantUserActions.createUserSuccess, TenantUserActions.updateUserSuccess, (state, {scope, user}) =>
        state.scope === scope ? {...state, groups: withGroupsOf(state.groups, user)} : state,
    ),
    on(TenantUserActions.deleteUserSuccess, (state, {scope, userId}) =>
        state.scope === scope ? {...state, groups: withoutMember(state.groups, userId)} : state,
    ),
    on(AreaActions.deleteAreaSuccess, (state, {scope, id}) =>
        state.scope === scope
            ? {...state, groups: state.groups.map(g => ({...g, areaIds: g.areaIds.filter(areaId => areaId !== id)}))}
            : state,
    ),
);
