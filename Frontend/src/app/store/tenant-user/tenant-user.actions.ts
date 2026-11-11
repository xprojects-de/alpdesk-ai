import {createAction, props} from "@ngrx/store";
import {TenantScope} from "../../models/tenant.model";
import {TenantUser, TenantUserRequest, UserFromTenantRequest} from "../../models/tenant-user.model";

export const loadUsers = createAction("[Tenant User] Load Users", props<{scope: TenantScope}>());
export const loadUsersSuccess = createAction(
    "[Tenant User] Load Users Success",
    props<{scope: TenantScope; users: TenantUser[]}>(),
);
export const loadUsersFailure = createAction("[Tenant User] Load Users Failure", props<{error: string}>());

export const createUser = createAction(
    "[Tenant User] Create User",
    props<{scope: TenantScope; user: TenantUserRequest}>(),
);
export const createUserSuccess = createAction(
    "[Tenant User] Create User Success",
    props<{scope: TenantScope; user: TenantUser}>(),
);
export const createUserFailure = createAction("[Tenant User] Create User Failure", props<{error: string}>());

/** The admin adds a person's user of another tenant - answered like a create (createUserSuccess / -Failure). */
export const addUserFromTenant = createAction(
    "[Tenant User] Add User From Tenant",
    props<{tenantId: number; request: UserFromTenantRequest}>(),
);

/** The users of another tenant the admin chooses from - kept apart from the users on screen. */
export const loadOtherTenantUsers = createAction("[Tenant User] Load Other Tenant Users", props<{tenantId: number}>());
export const loadOtherTenantUsersSuccess = createAction(
    "[Tenant User] Load Other Tenant Users Success",
    props<{tenantId: number; users: TenantUser[]}>(),
);
export const loadOtherTenantUsersFailure = createAction(
    "[Tenant User] Load Other Tenant Users Failure",
    props<{error: string}>(),
);

export const updateUser = createAction(
    "[Tenant User] Update User",
    props<{scope: TenantScope; userId: number; user: TenantUserRequest}>(),
);
export const updateUserSuccess = createAction(
    "[Tenant User] Update User Success",
    props<{scope: TenantScope; user: TenantUser}>(),
);
export const updateUserFailure = createAction("[Tenant User] Update User Failure", props<{error: string}>());

export const deleteUser = createAction("[Tenant User] Delete User", props<{scope: TenantScope; userId: number}>());
export const deleteUserSuccess = createAction(
    "[Tenant User] Delete User Success",
    props<{scope: TenantScope; userId: number}>(),
);
export const deleteUserFailure = createAction("[Tenant User] Delete User Failure", props<{error: string}>());
