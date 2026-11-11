import {createFeatureSelector, createSelector} from "@ngrx/store";
import {TenantState} from "./tenant.reducer";

export const selectTenantState = createFeatureSelector<TenantState>("tenant");

export const selectAllTenants = createSelector(selectTenantState, state => state.tenants);

export const selectTenant = (id: number) =>
    createSelector(selectAllTenants, tenants => tenants.find(tenant => tenant.id === id) ?? null);

export const selectTenantLoading = createSelector(selectTenantState, state => state.loading);

export const selectApiKeys = createSelector(selectTenantState, state => state.apiKeys.keys);

export const selectApiKeysLoading = createSelector(selectTenantState, state => state.apiKeys.loading);

/** The last check of a tenant's sandbox - the page compares its tenant id with its own. */
export const selectSandboxCheck = createSelector(selectTenantState, state => state.sandboxCheck);
