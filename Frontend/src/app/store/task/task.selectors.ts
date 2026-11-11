import {createFeatureSelector, createSelector} from "@ngrx/store";
import {TaskState} from "./task.reducer";

export const selectTaskState = createFeatureSelector<TaskState>("task");

export const selectTasks = createSelector(selectTaskState, state => state.tasks);
export const selectTasksLoading = createSelector(selectTaskState, state => state.loading);
export const selectCurrent = createSelector(selectTaskState, state => state.current);
export const selectCurrentLoading = createSelector(selectTaskState, state => state.currentLoading);
export const selectDraft = createSelector(selectTaskState, state => state.draft);
export const selectValidation = createSelector(selectTaskState, state => state.validation);
export const selectValidating = createSelector(selectTaskState, state => state.validating);
export const selectSaving = createSelector(selectTaskState, state => state.saving);
export const selectRuns = createSelector(selectTaskState, state => state.runs);
export const selectRunsLoading = createSelector(selectTaskState, state => state.runsLoading);
export const selectRun = createSelector(selectTaskState, state => state.run);
export const selectDraftModels = createSelector(selectTaskState, state => state.draftModels);
export const selectReference = createSelector(selectTaskState, state => state.reference);
