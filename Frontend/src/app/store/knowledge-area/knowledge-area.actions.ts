import {createAction, props} from "@ngrx/store";
import {KnowledgeArea} from "../../models/knowledge-area.model";
import {TenantScope} from "../../models/tenant.model";

export const loadAreas = createAction("[Knowledge Area] Load", props<{scope: TenantScope}>());
export const loadAreasSuccess = createAction(
    "[Knowledge Area] Load Success",
    props<{scope: TenantScope; areas: KnowledgeArea[]}>(),
);
export const loadAreasFailure = createAction("[Knowledge Area] Load Failure", props<{error: string}>());

export const createArea = createAction("[Knowledge Area] Create", props<{scope: TenantScope; name: string}>());
export const createAreaSuccess = createAction(
    "[Knowledge Area] Create Success",
    props<{scope: TenantScope; area: KnowledgeArea}>(),
);
export const createAreaFailure = createAction("[Knowledge Area] Create Failure", props<{error: string}>());

export const renameArea = createAction(
    "[Knowledge Area] Rename",
    props<{scope: TenantScope; id: number; name: string}>(),
);
export const renameAreaSuccess = createAction(
    "[Knowledge Area] Rename Success",
    props<{scope: TenantScope; area: KnowledgeArea}>(),
);
export const renameAreaFailure = createAction("[Knowledge Area] Rename Failure", props<{error: string}>());

export const deleteArea = createAction("[Knowledge Area] Delete", props<{scope: TenantScope; id: number}>());
export const deleteAreaSuccess = createAction(
    "[Knowledge Area] Delete Success",
    props<{scope: TenantScope; id: number}>(),
);
export const deleteAreaFailure = createAction("[Knowledge Area] Delete Failure", props<{error: string}>());
