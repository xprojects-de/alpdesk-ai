import {createFeatureSelector, createSelector} from "@ngrx/store";
import {TenantModelState} from "./tenant-model.reducer";

export const selectTenantModelState = createFeatureSelector<TenantModelState>("tenantModel");

export const selectTenantModelsLoading = createSelector(selectTenantModelState, state => state.loading);

/** The assigned model ids of this tenant; null while they are not loaded. */
export const selectAssignedModelIds = (tenantId: number) =>
    createSelector(selectTenantModelState, state => (state.tenantId === tenantId ? state.modelIds : null));
