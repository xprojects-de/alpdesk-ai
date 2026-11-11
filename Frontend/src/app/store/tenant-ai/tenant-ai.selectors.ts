import {createFeatureSelector, createSelector} from "@ngrx/store";
import {TenantAiState} from "./tenant-ai.reducer";

export const selectTenantAiState = createFeatureSelector<TenantAiState>("tenantAi");

export const selectTenantModels = createSelector(selectTenantAiState, state => state.models);

export const selectChatModels = createSelector(selectTenantModels, models => models.filter(m => m.kind === "CHAT"));

export const selectEmbeddingModels = createSelector(selectTenantModels, models =>
    models.filter(m => m.kind === "EMBEDDING"),
);

export const selectRerankModels = createSelector(selectTenantModels, models => models.filter(m => m.kind === "RERANK"));

export const selectAiSettings = createSelector(selectTenantAiState, state => state.settings);

export const selectEmbeddingModelSaving = createSelector(selectTenantAiState, state => state.saving);
export const selectSearchLanguageSaving = createSelector(selectTenantAiState, state => state.savingLanguage);
export const selectRerankerSaving = createSelector(selectTenantAiState, state => state.savingReranker);
