import {createAction, props} from "@ngrx/store";
import {AiSettings, TenantModel} from "../../models/ai.model";
import {SearchLanguage} from "../../models/tenant.model";

export const loadModels = createAction("[Tenant AI] Load Models");
export const loadModelsSuccess = createAction("[Tenant AI] Load Models Success", props<{models: TenantModel[]}>());
export const loadModelsFailure = createAction("[Tenant AI] Load Models Failure", props<{error: string}>());

export const loadSettings = createAction("[Tenant AI] Load Settings");
export const loadSettingsSuccess = createAction("[Tenant AI] Load Settings Success", props<{settings: AiSettings}>());
export const loadSettingsFailure = createAction("[Tenant AI] Load Settings Failure", props<{error: string}>());

export const setEmbeddingModel = createAction("[Tenant AI] Set Embedding Model", props<{modelId: number}>());
export const setEmbeddingModelSuccess = createAction(
    "[Tenant AI] Set Embedding Model Success",
    props<{settings: AiSettings}>(),
);
export const setEmbeddingModelFailure = createAction(
    "[Tenant AI] Set Embedding Model Failure",
    props<{error: string}>(),
);

/** A tenant admin switches the knowledge search's reranker on (a model's id) or off (null). */
export const setRerankingModel = createAction("[Tenant AI] Set Reranking Model", props<{modelId: number | null}>());
export const setRerankingModelSuccess = createAction(
    "[Tenant AI] Set Reranking Model Success",
    props<{settings: AiSettings}>(),
);
export const setRerankingModelFailure = createAction(
    "[Tenant AI] Set Reranking Model Failure",
    props<{error: string}>(),
);

/** A tenant admin chooses the language the full-text search reads the documents in. */
export const setSearchLanguage = createAction("[Tenant AI] Set Search Language", props<{language: SearchLanguage}>());
export const setSearchLanguageSuccess = createAction(
    "[Tenant AI] Set Search Language Success",
    props<{settings: AiSettings}>(),
);
export const setSearchLanguageFailure = createAction(
    "[Tenant AI] Set Search Language Failure",
    props<{error: string}>(),
);
