import {createReducer, on} from "@ngrx/store";
import {
    Task,
    TaskDraftModels,
    TaskRunDetail,
    TaskRunSummary,
    TaskSummary,
    TaskValidation,
} from "../../models/task.model";
import {TenantScope} from "../../models/tenant.model";
import * as TaskActions from "./task.actions";

/** One tenant's tasks at a time - the tenant admin's own, or the tenant the admin opened. */
export interface TaskState {
    scope: TenantScope | null;
    tasks: TaskSummary[];
    loading: boolean;
    /** The task open in the editor; null for a new one. */
    current: Task | null;
    currentLoading: boolean;
    /** The text a new task starts with (a template, a copy, an import). */
    draft: string | null;
    /** The last check of the editor's text. */
    validation: TaskValidation | null;
    validating: boolean;
    saving: boolean;
    runs: TaskRunSummary[];
    runsLoading: boolean;
    run: TaskRunDetail | null;
    draftModels: TaskDraftModels | null;
    reference: string | null;
}

export const initialState: TaskState = {
    scope: null,
    tasks: [],
    loading: false,
    current: null,
    currentLoading: false,
    draft: null,
    validation: null,
    validating: false,
    saving: false,
    runs: [],
    runsLoading: false,
    run: null,
    draftModels: null,
    reference: null,
};

/** A result for another tenant (the admin opened another one meanwhile) is ignored. */
function forScope(state: TaskState, scope: TenantScope, change: Partial<TaskState>): TaskState {
    return state.scope === scope ? {...state, ...change} : state;
}

function withScope(state: TaskState, scope: TenantScope): TaskState {
    return state.scope === scope ? state : {...initialState, scope};
}

export const taskReducer = createReducer(
    initialState,

    on(TaskActions.loadTasks, (state, {scope}) => ({...withScope(state, scope), loading: true})),
    on(TaskActions.loadTasksSuccess, (state, {scope, tasks}) => forScope(state, scope, {tasks, loading: false})),
    on(TaskActions.loadTasksFailure, state => ({...state, loading: false})),

    on(TaskActions.openTask, (state, {scope}) => ({
        ...withScope(state, scope),
        current: null,
        draft: null,
        validation: null,
        runs: [],
        currentLoading: true,
    })),
    on(TaskActions.openTaskSuccess, (state, {scope, task}) =>
        forScope(state, scope, {current: task, currentLoading: false}),
    ),
    on(TaskActions.loadTaskFailure, state => ({...state, currentLoading: false})),
    on(TaskActions.newTask, (state, {scope, source}) => ({
        ...withScope(state, scope),
        current: null,
        draft: source,
        validation: null,
        runs: [],
        currentLoading: false,
    })),

    on(TaskActions.validateTask, state => ({...state, validating: true})),
    on(TaskActions.validateTaskSuccess, (state, {scope, validation}) =>
        forScope(state, scope, {validation, validating: false}),
    ),
    on(TaskActions.validateTaskFailure, state => ({...state, validating: false})),

    on(TaskActions.saveTask, state => ({...state, saving: true})),
    on(TaskActions.saveTaskSuccess, (state, {scope, task}) =>
        forScope(state, scope, {current: task, draft: null, saving: false}),
    ),
    on(TaskActions.saveTaskFailure, state => ({...state, saving: false})),

    on(TaskActions.deleteTaskSuccess, (state, {scope, taskId}) =>
        forScope(state, scope, {tasks: state.tasks.filter(task => task.id !== taskId)}),
    ),

    on(TaskActions.loadRuns, state => ({...state, runsLoading: true})),
    on(TaskActions.loadRunsSuccess, (state, {scope, taskId, runs}) =>
        state.current?.id === taskId ? forScope(state, scope, {runs, runsLoading: false}) : state,
    ),
    on(TaskActions.loadRunsFailure, state => ({...state, runsLoading: false})),

    on(TaskActions.watchRun, (state, {scope}) => ({...withScope(state, scope), run: null})),
    on(TaskActions.loadRunSuccess, (state, {scope, run}) => forScope(state, scope, {run})),

    on(TaskActions.loadDraftModelsSuccess, (state, {scope, models}) => forScope(state, scope, {draftModels: models})),
    // A draft is checked already: its result is the editor's check, its steps the graph.
    on(TaskActions.draftTaskSuccess, (state, {scope, draft}) =>
        forScope(state, scope, {validation: {valid: draft.valid, errors: draft.errors, steps: draft.steps}}),
    ),
    on(TaskActions.loadReferenceSuccess, (state, {markdown}) => ({...state, reference: markdown})),
);
