import {createFeatureSelector, createSelector} from "@ngrx/store";
import {TenantUserState} from "./tenant-user.reducer";

export const selectTenantUserState = createFeatureSelector<TenantUserState>("tenantUser");

export const selectTenantUsers = createSelector(selectTenantUserState, state => state.users);

export const selectTenantUsersLoading = createSelector(selectTenantUserState, state => state.loading);

export const selectOtherTenantUsers = createSelector(selectTenantUserState, state => state.otherTenant?.users ?? []);
