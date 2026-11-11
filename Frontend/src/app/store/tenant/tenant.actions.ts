import {createAction, props} from "@ngrx/store";
import {
    ApiKey,
    ApiKeyRequest,
    SandboxInstance,
    SearchLanguage,
    Tenant,
    TenantRequest,
    TenantScope,
} from "../../models/tenant.model";

export const loadTenants = createAction("[Tenant] Load Tenants");
export const loadTenantsSuccess = createAction("[Tenant] Load Tenants Success", props<{tenants: Tenant[]}>());
export const loadTenantsFailure = createAction("[Tenant] Load Tenants Failure", props<{error: string}>());

export const createTenant = createAction("[Tenant] Create Tenant", props<{tenant: TenantRequest}>());
export const createTenantSuccess = createAction("[Tenant] Create Tenant Success", props<{tenant: Tenant}>());
export const createTenantFailure = createAction("[Tenant] Create Tenant Failure", props<{error: string}>());

export const updateTenant = createAction("[Tenant] Update Tenant", props<{id: number; tenant: TenantRequest}>());
export const updateTenantSuccess = createAction("[Tenant] Update Tenant Success", props<{tenant: Tenant}>());
export const updateTenantFailure = createAction("[Tenant] Update Tenant Failure", props<{error: string}>());

export const deleteTenant = createAction("[Tenant] Delete Tenant", props<{id: number}>());
export const deleteTenantSuccess = createAction("[Tenant] Delete Tenant Success", props<{id: number}>());
export const deleteTenantFailure = createAction("[Tenant] Delete Tenant Failure", props<{error: string}>());

/** The admin chooses the model the tenant's documents are embedded with (the company's knowledge needs one). */
export const setEmbeddingModel = createAction(
    "[Tenant] Set Embedding Model",
    props<{tenantId: number; modelId: number}>(),
);
export const setEmbeddingModelSuccess = createAction("[Tenant] Set Embedding Model Success", props<{tenant: Tenant}>());
export const setEmbeddingModelFailure = createAction("[Tenant] Set Embedding Model Failure", props<{error: string}>());

/** The admin switches the tenant's reranker on (a model's id) or off (null). */
export const setRerankingModel = createAction(
    "[Tenant] Set Reranking Model",
    props<{tenantId: number; modelId: number | null}>(),
);
export const setRerankingModelSuccess = createAction("[Tenant] Set Reranking Model Success", props<{tenant: Tenant}>());
export const setRerankingModelFailure = createAction("[Tenant] Set Reranking Model Failure", props<{error: string}>());

/** The admin chooses the language the tenant's full-text search reads its documents in. */
export const setSearchLanguage = createAction(
    "[Tenant] Set Search Language",
    props<{tenantId: number; language: SearchLanguage}>(),
);
export const setSearchLanguageSuccess = createAction("[Tenant] Set Search Language Success", props<{tenant: Tenant}>());
export const setSearchLanguageFailure = createAction("[Tenant] Set Search Language Failure", props<{error: string}>());

/** The admin's check of a tenant's own sandbox: every instance with the tenant's token. */
export const checkSandbox = createAction("[Tenant] Check Sandbox", props<{tenantId: number}>());
export const checkSandboxSuccess = createAction(
    "[Tenant] Check Sandbox Success",
    props<{tenantId: number; instances: SandboxInstance[]; extrasAgree: boolean}>(),
);
export const checkSandboxFailure = createAction("[Tenant] Check Sandbox Failure", props<{error: string}>());

export const loadApiKeys = createAction("[Tenant] Load API Keys", props<{scope: TenantScope}>());
export const loadApiKeysSuccess = createAction(
    "[Tenant] Load API Keys Success",
    props<{scope: TenantScope; keys: ApiKey[]}>(),
);
export const loadApiKeysFailure = createAction("[Tenant] Load API Keys Failure", props<{error: string}>());

export const createApiKey = createAction(
    "[Tenant] Create API Key",
    props<{scope: TenantScope; request: ApiKeyRequest}>(),
);
/**
 * Carries the full key exactly once, for the panel that shows it. The reducer deliberately keeps
 * only `key` - the plain key never enters the store state.
 */
export const createApiKeySuccess = createAction(
    "[Tenant] Create API Key Success",
    props<{scope: TenantScope; key: ApiKey; plainKey: string}>(),
);
export const createApiKeyFailure = createAction("[Tenant] Create API Key Failure", props<{error: string}>());

/** Renames a key or changes what it may use - its history, web search, areas, tasks, software. */
export const updateApiKey = createAction(
    "[Tenant] Update API Key",
    props<{scope: TenantScope; keyId: number; request: ApiKeyRequest}>(),
);
export const updateApiKeySuccess = createAction(
    "[Tenant] Update API Key Success",
    props<{scope: TenantScope; key: ApiKey}>(),
);
export const updateApiKeyFailure = createAction("[Tenant] Update API Key Failure", props<{error: string}>());

export const revokeApiKey = createAction("[Tenant] Revoke API Key", props<{scope: TenantScope; keyId: number}>());
export const revokeApiKeySuccess = createAction(
    "[Tenant] Revoke API Key Success",
    props<{scope: TenantScope; keyId: number}>(),
);
export const revokeApiKeyFailure = createAction("[Tenant] Revoke API Key Failure", props<{error: string}>());
