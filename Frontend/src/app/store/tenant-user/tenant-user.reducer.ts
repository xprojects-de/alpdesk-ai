import {createReducer, on} from "@ngrx/store";
import {TenantScope} from "../../models/tenant.model";
import {TenantUser} from "../../models/tenant-user.model";
import * as TenantUserActions from "./tenant-user.actions";
import * as LanguageActions from "../language/language.actions";
import * as GroupActions from "../user-group/user-group.actions";
import {withMembersOf, withoutGroup} from "../../utils/user-group.util";

/** The users of the tenant on screen - one tenant at a time (the tenant admin's own, or the one the admin opened). */
export interface TenantUserState {
    scope: TenantScope | null;
    users: TenantUser[];
    loading: boolean;
    /** The users of another tenant the admin chooses from ("add from another tenant"); null: none loaded. */
    otherTenant: {tenantId: number; users: TenantUser[]} | null;
}

export const initialState: TenantUserState = {
    scope: null,
    users: [],
    loading: false,
    otherTenant: null,
};

const byUsername = (a: TenantUser, b: TenantUser) => a.username.localeCompare(b.username);

export const tenantUserReducer = createReducer(
    initialState,

    on(TenantUserActions.loadUsers, (state, {scope}) => ({
        ...state,
        scope,
        users: state.scope === scope ? state.users : [],
        loading: true,
    })),
    on(TenantUserActions.loadOtherTenantUsers, (state, {tenantId}) => ({...state, otherTenant: {tenantId, users: []}})),
    on(TenantUserActions.loadOtherTenantUsersSuccess, (state, {tenantId, users}) =>
        state.otherTenant?.tenantId === tenantId
            ? {...state, otherTenant: {tenantId, users: [...users].sort(byUsername)}}
            : state,
    ),
    on(
        TenantUserActions.createUser,
        TenantUserActions.addUserFromTenant,
        TenantUserActions.updateUser,
        TenantUserActions.deleteUser,
        state => ({
            ...state,
            loading: true,
        }),
    ),
    on(
        TenantUserActions.loadUsersFailure,
        TenantUserActions.createUserFailure,
        TenantUserActions.updateUserFailure,
        TenantUserActions.deleteUserFailure,
        state => ({...state, loading: false}),
    ),
    // Results for another tenant (its dialog was closed and another one opened meanwhile) are ignored.
    on(TenantUserActions.loadUsersSuccess, (state, {scope, users}) =>
        state.scope === scope ? {...state, scope, users: [...users].sort(byUsername), loading: false} : state,
    ),
    on(TenantUserActions.createUserSuccess, (state, {scope, user}) =>
        state.scope === scope ? {...state, users: [...state.users, user].sort(byUsername), loading: false} : state,
    ),
    on(TenantUserActions.updateUserSuccess, (state, {scope, user}) =>
        state.scope === scope
            ? {...state, users: state.users.map(u => (u.id === user.id ? user : u)).sort(byUsername), loading: false}
            : state,
    ),
    on(TenantUserActions.deleteUserSuccess, (state, {scope, userId}) =>
        state.scope === scope ? {...state, users: state.users.filter(u => u.id !== userId), loading: false} : state,
    ),
    // A group saved with its members, or deleted, changes the users' groups - shown without loading them again.
    on(GroupActions.createGroupSuccess, GroupActions.updateGroupSuccess, (state, {scope, group}) =>
        state.scope === scope ? {...state, users: withMembersOf(state.users, group)} : state,
    ),
    on(GroupActions.deleteGroupSuccess, (state, {scope, id}) =>
        state.scope === scope ? {...state, users: withoutGroup(state.users, id)} : state,
    ),
    // A tenant admin's own choice in the menu: its row shows it - and the dialog does not send the old one back.
    on(LanguageActions.chooseLanguageSuccess, (state, {userId, language}) => ({
        ...state,
        users: state.users.map(u => (u.id === userId ? {...u, language} : u)),
    })),
);
