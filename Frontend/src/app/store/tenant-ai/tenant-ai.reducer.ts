import {createReducer, on} from "@ngrx/store";
import {AiSettings, TenantModel} from "../../models/ai.model";
import * as TenantAiActions from "./tenant-ai.actions";

export interface TenantAiState {
    models: TenantModel[];
    settings: AiSettings | null;
    /** The embedding model is being changed. */
    saving: boolean;
    /** The search language is being changed. */
    savingLanguage: boolean;
    /** The reranker is being changed. */
    savingReranker: boolean;
}

export const initialState: TenantAiState = {
    models: [],
    settings: null,
    saving: false,
    savingLanguage: false,
    savingReranker: false,
};

export const tenantAiReducer = createReducer(
    initialState,

    on(TenantAiActions.loadModelsSuccess, (state, {models}) => ({...state, models})),
    on(TenantAiActions.loadSettingsSuccess, (state, {settings}) => ({...state, settings})),
    on(TenantAiActions.setEmbeddingModel, state => ({...state, saving: true})),
    on(TenantAiActions.setEmbeddingModelSuccess, (state, {settings}) => ({...state, settings, saving: false})),
    on(TenantAiActions.setEmbeddingModelFailure, state => ({...state, saving: false})),
    on(TenantAiActions.setSearchLanguage, state => ({...state, savingLanguage: true})),
    on(TenantAiActions.setSearchLanguageSuccess, (state, {settings}) => ({...state, settings, savingLanguage: false})),
    on(TenantAiActions.setSearchLanguageFailure, state => ({...state, savingLanguage: false})),
    on(TenantAiActions.setRerankingModel, state => ({...state, savingReranker: true})),
    on(TenantAiActions.setRerankingModelSuccess, (state, {settings}) => ({...state, settings, savingReranker: false})),
    on(TenantAiActions.setRerankingModelFailure, state => ({...state, savingReranker: false})),
);
