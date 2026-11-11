import {createAction, props} from "@ngrx/store";
import {TenantScope} from "../../models/tenant.model";
import {UserGroup, UserGroupRequest} from "../../models/user-group.model";

export const loadGroups = createAction("[User Group] Load", props<{scope: TenantScope}>());
export const loadGroupsSuccess = createAction(
    "[User Group] Load Success",
    props<{scope: TenantScope; groups: UserGroup[]}>(),
);
export const loadGroupsFailure = createAction("[User Group] Load Failure", props<{error: string}>());

export const createGroup = createAction("[User Group] Create", props<{scope: TenantScope; group: UserGroupRequest}>());
export const createGroupSuccess = createAction(
    "[User Group] Create Success",
    props<{scope: TenantScope; group: UserGroup}>(),
);
export const createGroupFailure = createAction("[User Group] Create Failure", props<{error: string}>());

export const updateGroup = createAction(
    "[User Group] Update",
    props<{scope: TenantScope; id: number; group: UserGroupRequest}>(),
);
export const updateGroupSuccess = createAction(
    "[User Group] Update Success",
    props<{scope: TenantScope; group: UserGroup}>(),
);
export const updateGroupFailure = createAction("[User Group] Update Failure", props<{error: string}>());

export const deleteGroup = createAction("[User Group] Delete", props<{scope: TenantScope; id: number}>());
export const deleteGroupSuccess = createAction(
    "[User Group] Delete Success",
    props<{scope: TenantScope; id: number}>(),
);
export const deleteGroupFailure = createAction("[User Group] Delete Failure", props<{error: string}>());
