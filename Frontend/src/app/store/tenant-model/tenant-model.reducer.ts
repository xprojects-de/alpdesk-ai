import {createReducer, on} from "@ngrx/store";
import * as TenantModelActions from "./tenant-model.actions";

/**
 * The models assigned to the tenant whose model dialog is open - one tenant at a time, like its users. `modelIds` is
 * null until they are loaded: the dialog must not offer to save a selection it has not seen.
 */
export interface TenantModelState {
    tenantId: number | null;
    modelIds: number[] | null;
    loading: boolean;
}

export const initialState: TenantModelState = {
    tenantId: null,
    modelIds: null,
    loading: false,
};

export const tenantModelReducer = createReducer(
    initialState,

    on(TenantModelActions.loadTenantModels, (_state, {tenantId}) => ({tenantId, modelIds: null, loading: true})),
    on(TenantModelActions.assignTenantModels, state => ({...state, loading: true})),
    on(TenantModelActions.loadTenantModelsFailure, TenantModelActions.assignTenantModelsFailure, state => ({
        ...state,
        loading: false,
    })),
    // Results for another tenant (its dialog was closed and another one opened meanwhile) are ignored.
    on(
        TenantModelActions.loadTenantModelsSuccess,
        TenantModelActions.assignTenantModelsSuccess,
        (state, {tenantId, modelIds}) => (state.tenantId === tenantId ? {tenantId, modelIds, loading: false} : state),
    ),
);
