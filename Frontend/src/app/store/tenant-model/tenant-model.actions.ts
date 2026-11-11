import {createAction, props} from "@ngrx/store";

export const loadTenantModels = createAction("[Tenant Model] Load Tenant Models", props<{tenantId: number}>());
export const loadTenantModelsSuccess = createAction(
    "[Tenant Model] Load Tenant Models Success",
    props<{tenantId: number; modelIds: number[]}>(),
);
export const loadTenantModelsFailure = createAction(
    "[Tenant Model] Load Tenant Models Failure",
    props<{error: string}>(),
);

export const assignTenantModels = createAction(
    "[Tenant Model] Assign Tenant Models",
    props<{tenantId: number; modelIds: number[]}>(),
);
export const assignTenantModelsSuccess = createAction(
    "[Tenant Model] Assign Tenant Models Success",
    props<{tenantId: number; modelIds: number[]}>(),
);
export const assignTenantModelsFailure = createAction(
    "[Tenant Model] Assign Tenant Models Failure",
    props<{error: string}>(),
);
