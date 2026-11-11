import {createFeatureSelector, createSelector} from "@ngrx/store";
import {AssistantState} from "./assistant.reducer";

export const selectAssistantState = createFeatureSelector<AssistantState>("assistant");

export const selectSettings = createSelector(selectAssistantState, state => state.settings);

export const selectTemplates = createSelector(selectAssistantState, state => state.templates);

export const selectModels = createSelector(selectAssistantState, state => state.models);

export const selectImageModels = createSelector(selectAssistantState, state => state.imageModels);

export const selectAssistantLoading = createSelector(selectAssistantState, state => state.loading);

export const selectSavingSettings = createSelector(selectAssistantState, state => state.savingSettings);
