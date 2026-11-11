import {createAction, props} from "@ngrx/store";
import {
    Task,
    TaskDraft,
    TaskDraftModels,
    TaskDraftRequest,
    TaskRequest,
    TaskRunDetail,
    TaskRunSummary,
    TaskSummary,
    TaskValidation,
} from "../../models/task.model";
import {TenantScope} from "../../models/tenant.model";

export const loadTasks = createAction("[Task] Load", props<{scope: TenantScope}>());
export const loadTasksSuccess = createAction(
    "[Task] Load Success",
    props<{scope: TenantScope; tasks: TaskSummary[]}>(),
);
export const loadTasksFailure = createAction("[Task] Load Failure", props<{error: string}>());

/** The editor opens a task - or a new one (task null) with this text. */
export const openTask = createAction("[Task] Open", props<{scope: TenantScope; taskId: number}>());
export const openTaskSuccess = createAction("[Task] Open Success", props<{scope: TenantScope; task: Task}>());
export const loadTaskFailure = createAction("[Task] Load One Failure", props<{error: string}>());
export const newTask = createAction("[Task] New", props<{scope: TenantScope; source: string}>());

export const validateTask = createAction("[Task] Validate", props<{scope: TenantScope; source: string}>());
export const validateTaskSuccess = createAction(
    "[Task] Validate Success",
    props<{scope: TenantScope; validation: TaskValidation}>(),
);
export const validateTaskFailure = createAction("[Task] Validate Failure", props<{error: string}>());

/** taskId null: a new task. */
export const saveTask = createAction(
    "[Task] Save",
    props<{scope: TenantScope; taskId: number | null; request: TaskRequest}>(),
);
export const saveTaskSuccess = createAction("[Task] Save Success", props<{scope: TenantScope; task: Task}>());
export const saveTaskFailure = createAction("[Task] Save Failure", props<{error: string}>());

export const deleteTask = createAction("[Task] Delete", props<{scope: TenantScope; taskId: number}>());
export const deleteTaskSuccess = createAction("[Task] Delete Success", props<{scope: TenantScope; taskId: number}>());
export const deleteTaskFailure = createAction("[Task] Delete Failure", props<{error: string}>());

export const startTask = createAction("[Task] Start", props<{scope: TenantScope; taskId: number}>());
export const startTaskSuccess = createAction(
    "[Task] Start Success",
    props<{scope: TenantScope; taskId: number; runId: number}>(),
);
export const startTaskFailure = createAction("[Task] Start Failure", props<{error: string}>());

export const loadRuns = createAction("[Task] Load Runs", props<{scope: TenantScope; taskId: number}>());
export const loadRunsSuccess = createAction(
    "[Task] Load Runs Success",
    props<{scope: TenantScope; taskId: number; runs: TaskRunSummary[]}>(),
);
export const loadRunsFailure = createAction("[Task] Load Runs Failure", props<{error: string}>());

/** The run view: loaded, and loaded again every few seconds while the run is open. */
export const watchRun = createAction("[Task] Watch Run", props<{scope: TenantScope; taskId: number; runId: number}>());
export const watchRunStop = createAction("[Task] Watch Run Stop");
export const loadRunSuccess = createAction(
    "[Task] Load Run Success",
    props<{scope: TenantScope; run: TaskRunDetail}>(),
);
export const loadRunFailure = createAction("[Task] Load Run Failure", props<{error: string}>());

export const cancelRun = createAction(
    "[Task] Cancel Run",
    props<{scope: TenantScope; taskId: number; runId: number}>(),
);
export const cancelRunSuccess = createAction(
    "[Task] Cancel Run Success",
    props<{scope: TenantScope; taskId: number; runId: number}>(),
);
export const cancelRunFailure = createAction("[Task] Cancel Run Failure", props<{error: string}>());

/** runId null: every ended run of the task. */
export const deleteRuns = createAction(
    "[Task] Delete Runs",
    props<{scope: TenantScope; taskId: number; runId: number | null}>(),
);
export const deleteRunsSuccess = createAction(
    "[Task] Delete Runs Success",
    props<{scope: TenantScope; taskId: number}>(),
);
export const deleteRunsFailure = createAction("[Task] Delete Runs Failure", props<{error: string}>());

/** "Draft with AI": the models to choose from, the draft itself (never saved). */
export const loadDraftModels = createAction("[Task] Load Draft Models", props<{scope: TenantScope}>());
export const loadDraftModelsSuccess = createAction(
    "[Task] Load Draft Models Success",
    props<{scope: TenantScope; models: TaskDraftModels}>(),
);
export const loadDraftModelsFailure = createAction("[Task] Load Draft Models Failure", props<{error: string}>());
export const draftTask = createAction("[Task] Draft", props<{scope: TenantScope; request: TaskDraftRequest}>());
export const draftTaskSuccess = createAction("[Task] Draft Success", props<{scope: TenantScope; draft: TaskDraft}>());
export const draftTaskFailure = createAction("[Task] Draft Failure", props<{error: string}>());

/** The format's reference in a language, from the backend - the same text a draft's model is told. */
export const loadReference = createAction("[Task] Load Reference", props<{scope: TenantScope; language: string}>());
export const loadReferenceSuccess = createAction("[Task] Load Reference Success", props<{markdown: string}>());
export const loadReferenceFailure = createAction("[Task] Load Reference Failure", props<{error: string}>());
