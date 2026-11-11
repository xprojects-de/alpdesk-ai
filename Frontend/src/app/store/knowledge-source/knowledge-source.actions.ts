import {createAction, props} from "@ngrx/store";
import {KnowledgeSource, KnowledgeSourceRequest, SourceFile} from "../../models/knowledge-source.model";
import {TenantScope} from "../../models/tenant.model";

export const loadSources = createAction("[Knowledge Source] Load", props<{scope: TenantScope}>());
/** Reloads without the loading bar - while a source runs, every few seconds. */
export const pollSources = createAction("[Knowledge Source] Poll", props<{scope: TenantScope}>());
export const loadSourcesSuccess = createAction(
    "[Knowledge Source] Load Success",
    props<{scope: TenantScope; sources: KnowledgeSource[]}>(),
);
export const loadSourcesFailure = createAction("[Knowledge Source] Load Failure", props<{error: string}>());

export const startPolling = createAction("[Knowledge Source] Start Polling", props<{scope: TenantScope}>());
export const stopPolling = createAction("[Knowledge Source] Stop Polling");

export const createSource = createAction(
    "[Knowledge Source] Create",
    props<{scope: TenantScope; request: KnowledgeSourceRequest}>(),
);
export const createSourceSuccess = createAction(
    "[Knowledge Source] Create Success",
    props<{scope: TenantScope; source: KnowledgeSource}>(),
);
export const createSourceFailure = createAction("[Knowledge Source] Create Failure", props<{error: string}>());

export const updateSource = createAction(
    "[Knowledge Source] Update",
    props<{scope: TenantScope; sourceId: number; request: KnowledgeSourceRequest}>(),
);
export const updateSourceSuccess = createAction(
    "[Knowledge Source] Update Success",
    props<{scope: TenantScope; source: KnowledgeSource}>(),
);
export const updateSourceFailure = createAction("[Knowledge Source] Update Failure", props<{error: string}>());

export const deleteSource = createAction(
    "[Knowledge Source] Delete",
    props<{scope: TenantScope; sourceId: number; keepDocuments: boolean}>(),
);
export const deleteSourceSuccess = createAction(
    "[Knowledge Source] Delete Success",
    props<{scope: TenantScope; sourceId: number}>(),
);
export const deleteSourceFailure = createAction("[Knowledge Source] Delete Failure", props<{error: string}>());

export const runSource = createAction("[Knowledge Source] Run", props<{scope: TenantScope; sourceId: number}>());
export const runSourceSuccess = createAction(
    "[Knowledge Source] Run Success",
    props<{scope: TenantScope; source: KnowledgeSource}>(),
);
export const runSourceFailure = createAction("[Knowledge Source] Run Failure", props<{error: string}>());

export const cancelSource = createAction("[Knowledge Source] Cancel", props<{scope: TenantScope; sourceId: number}>());
export const cancelSourceSuccess = createAction("[Knowledge Source] Cancel Success");
export const cancelSourceFailure = createAction("[Knowledge Source] Cancel Failure", props<{error: string}>());

/** The source's files without a document - refused, or deleted by hand. */
export const loadFiles = createAction("[Knowledge Source] Load Files", props<{scope: TenantScope; sourceId: number}>());
export const loadFilesSuccess = createAction(
    "[Knowledge Source] Load Files Success",
    props<{scope: TenantScope; sourceId: number; files: SourceFile[]}>(),
);
export const loadFilesFailure = createAction("[Knowledge Source] Load Files Failure", props<{error: string}>());
