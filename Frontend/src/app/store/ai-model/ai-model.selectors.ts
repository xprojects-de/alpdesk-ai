import {createFeatureSelector, createSelector} from "@ngrx/store";
import {AiModelState} from "./ai-model.reducer";

export const selectAiModelState = createFeatureSelector<AiModelState>("aiModel");

export const selectAiModels = createSelector(selectAiModelState, state => state.models);

export const selectAiModelsLoading = createSelector(selectAiModelState, state => state.loading);
