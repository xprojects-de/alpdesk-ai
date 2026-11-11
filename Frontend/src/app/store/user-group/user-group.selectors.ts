import {createFeatureSelector, createSelector} from "@ngrx/store";
import {UserGroupState} from "./user-group.reducer";

export const selectUserGroupState = createFeatureSelector<UserGroupState>("userGroup");

export const selectGroups = createSelector(selectUserGroupState, state => state.groups);

export const selectGroupsLoading = createSelector(selectUserGroupState, state => state.loading);
